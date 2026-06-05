package org.mendrugo.attimo.aws;

import org.mendrugo.attimo.config.RegionGroup;
import org.mendrugo.attimo.isa.IsaFeature;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Analyses spot pricing across regions and selects the best instance
 * for a given ISA feature. Balances cost, instance size (larger instances
 * tend to have lower interruption rates), and region proximity.
 */
public class SpotAdvisor
{
    // Instance sizes to consider, from smallest to largest
    private static final String[] SIZES = {
        "large"
        , "xlarge"
        , "2xlarge"
        , "4xlarge"
    };

    // Proximity preference: if a closer region is within this percentage
    // of the cheapest, prefer the closer region
    private static final double PROXIMITY_THRESHOLD = 0.15;

    // Bias toward larger instances (lower interruption rate).
    // Applied as a discount factor to the score of larger instances.
    private static final double SIZE_BIAS_PER_STEP = 0.03;

    private final Function<String, Ec2Client> ec2Factory;

    /**
     * @param ec2Factory creates an Ec2Client for a given region
     */
    public SpotAdvisor(final Function<String, Ec2Client> ec2Factory)
    {
        this.ec2Factory = ec2Factory;
    }

    /**
     * Find the best spot instance for the given ISA feature.
     *
     * @param feature        the ISA feature defining candidate instance families
     * @param preferredRegion the user's preferred region
     * @return the best recommendation, or null if no spot instances are available
     */
    public SpotRecommendation recommend(
        final IsaFeature feature
        , final String preferredRegion
    )
    {
        final var regionGroup = RegionGroup.forRegion(preferredRegion);
        final var candidateTypes = expandToInstanceTypes(feature.families());

        if (candidateTypes.isEmpty())
        {
            return null;
        }

        final var allPrices = new ArrayList<PricedCandidate>();

        for (final String region : regionGroup.regions())
        {
            try (final var ec2 = ec2Factory.apply(region))
            {
                final var prices = querySpotPrices(ec2, candidateTypes, region);
                allPrices.addAll(prices);
            }
            catch (final Exception e)
            {
                System.err.println("  Warning: could not query spot prices in "
                    + region + ": " + e.getMessage());
            }
        }

        if (allPrices.isEmpty())
        {
            return null;
        }

        return selectBest(allPrices, preferredRegion);
    }

    /**
     * Expand instance families to specific instance types.
     * E.g., "c7i" → ["c7i.large", "c7i.xlarge", "c7i.2xlarge", "c7i.4xlarge"]
     */
    List<String> expandToInstanceTypes(final List<String> families)
    {
        final var types = new ArrayList<String>();
        for (final String family : families)
        {
            for (final String size : SIZES)
            {
                types.add(family + "." + size);
            }
        }

        return types;
    }

    private List<PricedCandidate> querySpotPrices(
        final Ec2Client ec2
        , final List<String> instanceTypes
        , final String region
    )
    {
        final var results = new ArrayList<PricedCandidate>();

        // Query in batches (AWS limits instance type filter)
        final int batchSize = 20;
        for (int i = 0; i < instanceTypes.size(); i += batchSize)
        {
            final var batch = instanceTypes.subList(
                i
                , Math.min(i + batchSize, instanceTypes.size())
            );

            try
            {
                final var response = ec2.describeSpotPriceHistory(
                    DescribeSpotPriceHistoryRequest.builder()
                        .instanceTypesWithStrings(batch)
                        .productDescriptions("Linux/UNIX")
                        .startTime(Instant.now())
                        .maxResults(batch.size() * 3)
                        .build()
                );

                for (final SpotPrice sp : response.spotPriceHistory())
                {
                    try
                    {
                        final double price = Double.parseDouble(sp.spotPrice());
                        results.add(new PricedCandidate(
                            sp.instanceTypeAsString()
                            , region
                            , sp.availabilityZone()
                            , price
                        ));
                    }
                    catch (final NumberFormatException ignored)
                    {
                        // Skip malformed price entries
                    }
                }
            }
            catch (final Exception e)
            {
                System.err.println("  Warning: spot price query failed for batch in "
                    + region + ": " + e.getMessage());
            }
        }

        return results;
    }

    SpotRecommendation selectBest(
        final List<PricedCandidate> candidates
        , final String preferredRegion
    )
    {
        // Score each candidate: lower is better
        final var scored = new ArrayList<ScoredCandidate>();

        for (final var candidate : candidates)
        {
            double score = candidate.price;

            // Size bias: prefer larger instances (lower interruption rate)
            final int sizeIndex = sizeIndex(candidate.instanceType);
            score *= (1.0 - sizeIndex * SIZE_BIAS_PER_STEP);

            // Proximity: penalize non-preferred regions slightly
            if (!candidate.region.equals(preferredRegion))
            {
                score *= (1.0 + PROXIMITY_THRESHOLD);
            }

            scored.add(new ScoredCandidate(candidate, score));
        }

        scored.sort(Comparator.comparingDouble(s -> s.score));

        final var best = scored.getFirst();
        final var c = best.candidate;

        final var rationale = new StringBuilder();
        rationale.append(c.instanceType)
            .append(" in ").append(c.availabilityZone)
            .append(" @ $").append(String.format("%.4f", c.price)).append("/hr");

        if (!c.region.equals(preferredRegion))
        {
            rationale.append(" (outside preferred region ").append(preferredRegion).append(")");
        }

        return new SpotRecommendation(
            c.instanceType
            , c.region
            , c.availabilityZone
            , c.price
            , rationale.toString()
        );
    }

    private int sizeIndex(final String instanceType)
    {
        final var parts = instanceType.split("\\.");
        if (parts.length < 2)
        {
            return 0;
        }

        final var size = parts[1];
        for (int i = 0; i < SIZES.length; i++)
        {
            if (SIZES[i].equals(size))
            {
                return i;
            }
        }

        return 0;
    }

    record PricedCandidate(
        String instanceType
        , String region
        , String availabilityZone
        , double price
    )
    {}

    private record ScoredCandidate(
        PricedCandidate candidate
        , double score
    )
    {}
}
