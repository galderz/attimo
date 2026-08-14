package org.mendrugo.attimo.aws;

import org.mendrugo.attimo.config.Continent;
import org.mendrugo.attimo.isa.IsaFeature;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Analyses spot pricing across continents and selects the best instances
 * for a given ISA feature. Returns a ranked list of recommendations so
 * that callers can retry with the next-best option on capacity failure.
 *
 * <p>Scoring tiers (lower penalty = higher preference):
 * <ul>
 *   <li>Tier 0: user's exact preferred region (0% penalty)</li>
 *   <li>Tier 1: other regions in the user's continent (+10%)</li>
 *   <li>Tier 2: cheaper foreign continent (+25%)</li>
 *   <li>Tier 3: more expensive foreign continent (+40%)</li>
 * </ul>
 */
public class SpotAdvisor
{
    // Default instance size tier
    private static final InstanceSize DEFAULT_SIZE = InstanceSize.DEFAULT;

    // Tier penalties for proximity scoring
    static final double TIER_1_PENALTY = 0.10; // same continent, different region
    static final double TIER_2_PENALTY = 0.25; // cheaper foreign continent
    static final double TIER_3_PENALTY = 0.40; // more expensive foreign continent

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
     * Find the best spot instance using the default size (medium).
     */
    public List<SpotRecommendation> recommend(
        final IsaFeature feature
        , final String preferredRegion
    )
    {
        return recommend(feature, preferredRegion, DEFAULT_SIZE);
    }

    /**
     * Find ranked spot instance options across all continents.
     *
     * <p>Queries all regions in the user's home continent plus
     * representative regions in the other two continents. Returns
     * a ranked list (best first) so callers can retry on capacity
     * failure.
     *
     * @param feature         the ISA feature defining candidate instance families
     * @param preferredRegion the user's preferred region
     * @param size            the instance size tier controlling vCPU/RAM
     * @return ranked list of recommendations (best first), empty if none available
     */
    public List<SpotRecommendation> recommend(
        final IsaFeature feature
        , final String preferredRegion
        , final InstanceSize size
    )
    {
        final var homeContinent = Continent.forRegion(preferredRegion);
        final var candidateTypes = expandToInstanceTypes(feature.families(), size);

        if (candidateTypes.isEmpty())
        {
            return List.of();
        }

        // Collect prices per continent
        final var pricesPerContinent = new HashMap<Continent, List<PricedCandidate>>();

        // Query all regions in home continent
        final var homePrices = queryContinent(
            homeContinent.regions()
            , candidateTypes
        );
        pricesPerContinent.put(homeContinent, homePrices);

        // Query representative regions in foreign continents
        for (final Continent foreign : homeContinent.others())
        {
            final var foreignPrices = queryContinent(
                foreign.representatives()
                , candidateTypes
            );
            pricesPerContinent.put(foreign, foreignPrices);
        }

        // Determine foreign continent priority (cheaper first)
        final var foreignContinents = homeContinent.others();
        final var foreignOrder = rankForeignContinents(
            foreignContinents
            , pricesPerContinent
        );

        // Score all candidates with tiered penalties
        return scoreAndRank(
            pricesPerContinent
            , preferredRegion
            , homeContinent
            , foreignOrder
        );
    }

    /**
     * Expand instance families to specific instance types using the default size.
     */
    List<String> expandToInstanceTypes(final List<String> families)
    {
        return expandToInstanceTypes(families, DEFAULT_SIZE);
    }

    /**
     * Expand instance families to specific instance types for a given size tier.
     * E.g., "c7i" with MEDIUM → ["c7i.4xlarge", "c7i.8xlarge"]
     */
    List<String> expandToInstanceTypes(
        final List<String> families
        , final InstanceSize size
    )
    {
        final var types = new ArrayList<String>();
        for (final String family : families)
        {
            for (final String awsSize : size.awsSizes())
            {
                types.add(family + "." + awsSize);
            }
        }

        return types;
    }

    private List<PricedCandidate> queryContinent(
        final List<String> regions
        , final List<String> candidateTypes
    )
    {
        final var allPrices = new ArrayList<PricedCandidate>();

        for (final String region : regions)
        {
            try (final var ec2 = ec2Factory.apply(region))
            {
                final var prices = querySpotPrices(ec2, candidateTypes, region);
                allPrices.addAll(prices);
            }
            catch (final Exception e)
            {
                final var msg = e.getMessage();
                if (msg != null && (msg.contains("401") || msg.contains("AuthFailure")
                    || msg.contains("OptInRequired")))
                {
                    System.out.println("  Skipping " + region + " (region not enabled in your account)");
                }
                else
                {
                    System.err.println("  Warning: could not query spot prices in "
                        + region + ": " + msg);
                }
            }
        }

        return allPrices;
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

    /**
     * Rank foreign continents by median spot price (cheapest first).
     * A continent with no prices goes last.
     */
    List<Continent> rankForeignContinents(
        final List<Continent> foreignContinents
        , final Map<Continent, List<PricedCandidate>> pricesPerContinent
    )
    {
        final var sorted = new ArrayList<>(foreignContinents);
        sorted.sort(Comparator.comparingDouble(c ->
            medianPrice(pricesPerContinent.getOrDefault(c, List.of()))
        ));

        return sorted;
    }

    /**
     * Score all candidates with tiered penalties and return a ranked list.
     */
    List<SpotRecommendation> scoreAndRank(
        final Map<Continent, List<PricedCandidate>> pricesPerContinent
        , final String preferredRegion
        , final Continent homeContinent
        , final List<Continent> foreignOrder
    )
    {
        final var scored = new ArrayList<ScoredCandidate>();

        for (final var entry : pricesPerContinent.entrySet())
        {
            final var continent = entry.getKey();

            for (final var candidate : entry.getValue())
            {
                double score = candidate.price;

                // Size bias: prefer larger instances (lower interruption rate)
                final int sizeIdx = sizeIndex(candidate.instanceType);
                score *= (1.0 - sizeIdx * SIZE_BIAS_PER_STEP);

                // Continent/region proximity penalty
                if (continent == homeContinent && candidate.region.equals(preferredRegion))
                {
                    // Tier 0: no penalty
                }
                else if (continent == homeContinent)
                {
                    score *= (1.0 + TIER_1_PENALTY);
                }
                else
                {
                    score *= (1.0 + foreignPenalty(continent, foreignOrder));
                }

                scored.add(new ScoredCandidate(candidate, score));
            }
        }

        scored.sort(Comparator.comparingDouble(s -> s.score));

        final var results = new ArrayList<SpotRecommendation>();
        for (final var s : scored)
        {
            final var c = s.candidate;
            final var rationale = buildRationale(c, preferredRegion, homeContinent);
            results.add(new SpotRecommendation(
                c.instanceType
                , c.region
                , c.availabilityZone
                , c.price
                , rationale
            ));
        }

        return results;
    }

    /**
     * Determine the penalty for a foreign continent.
     * Only called for non-home continents; home continent
     * scoring is handled directly in {@link #scoreAndRank}.
     *
     * @param foreignContinent the foreign continent being scored
     * @param foreignOrder     foreign continents ranked by median price (cheapest first)
     * @return TIER_2_PENALTY for the cheapest foreign continent, TIER_3_PENALTY otherwise
     */
    private double foreignPenalty(
        final Continent foreignContinent
        , final List<Continent> foreignOrder
    )
    {
        if (!foreignOrder.isEmpty() && foreignOrder.getFirst() == foreignContinent)
        {
            return TIER_2_PENALTY;
        }

        return TIER_3_PENALTY;
    }

    private String buildRationale(
        final PricedCandidate candidate
        , final String preferredRegion
        , final Continent homeContinent
    )
    {
        final var sb = new StringBuilder();
        sb.append(candidate.instanceType)
            .append(" in ").append(candidate.availabilityZone)
            .append(" @ $").append(String.format("%.4f", candidate.price)).append("/hr");

        final var candidateContinent = Continent.forRegion(candidate.region);
        if (candidateContinent != homeContinent)
        {
            sb.append(" (fallback continent: ").append(candidateContinent.displayName()).append(")");
        }
        else if (!candidate.region.equals(preferredRegion))
        {
            sb.append(" (outside preferred region ").append(preferredRegion).append(")");
        }

        return sb.toString();
    }

    static double medianPrice(final List<PricedCandidate> prices)
    {
        if (prices.isEmpty())
        {
            return Double.MAX_VALUE;
        }

        final var sorted = prices.stream()
            .mapToDouble(p -> p.price)
            .sorted()
            .toArray();

        final int mid = sorted.length / 2;
        if (sorted.length % 2 == 0)
        {
            return (sorted[mid - 1] + sorted[mid]) / 2.0;
        }

        return sorted[mid];
    }

    private static final List<String> ALL_SIZES = List.of(
        "large", "xlarge", "2xlarge", "4xlarge"
        , "8xlarge", "12xlarge", "16xlarge"
    );

    private int sizeIndex(final String instanceType)
    {
        final var parts = instanceType.split("\\.");
        if (parts.length < 2)
        {
            throw new IllegalArgumentException(
                "Malformed instance type (expected family.size): " + instanceType
            );
        }

        final var size = parts[1];
        final int index = ALL_SIZES.indexOf(size);
        if (index < 0)
        {
            throw new IllegalArgumentException(
                "Unknown instance size '" + size + "' in " + instanceType
                    + ". Known sizes: " + ALL_SIZES
            );
        }

        return index;
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
