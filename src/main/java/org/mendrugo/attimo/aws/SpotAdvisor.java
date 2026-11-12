package org.mendrugo.attimo.aws;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.mendrugo.attimo.isa.IsaFeature;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

public class SpotAdvisor {

    private static final InstanceSize DEFAULT_SIZE = InstanceSize.DEFAULT;

    static final double TIER_1_PENALTY = 0.10;
    static final double TIER_2_PENALTY = 0.25;
    static final double TIER_3_PENALTY = 0.40;

    private static final double SIZE_BIAS_PER_STEP = 0.03;

    private final Function<String, Ec2Client> ec2Factory;

    public SpotAdvisor(final Function<String, Ec2Client> ec2Factory) {
        this.ec2Factory = ec2Factory;
    }

    public List<SpotRecommendation> recommend(final IsaFeature feature, final String preferredRegion) {
        return recommend(feature, preferredRegion, DEFAULT_SIZE);
    }

    public List<SpotRecommendation> recommend(final IsaFeature feature, final String preferredRegion, final InstanceSize size) {
        final var homeContinent = Continent.forRegion(preferredRegion);
        final var candidateTypes = expandToInstanceTypes(feature.families(), size);

        if (candidateTypes.isEmpty()) {
            return List.of();
        }

        final var pricesPerContinent = new HashMap<Continent, List<PricedCandidate>>();

        final var homePrices = queryContinent(homeContinent.regionCodes(), candidateTypes);
        pricesPerContinent.put(homeContinent, homePrices);

        for (final Continent foreign : homeContinent.others()) {
            final var foreignPrices = queryContinent(foreign.representativeCodes(), candidateTypes);
            pricesPerContinent.put(foreign, foreignPrices);
        }

        final var foreignContinents = homeContinent.others();
        final var foreignOrder = rankForeignContinents(foreignContinents, pricesPerContinent);

        return scoreAndRank(pricesPerContinent, preferredRegion, homeContinent, foreignOrder);
    }

    List<String> expandToInstanceTypes(final List<String> families) {
        return expandToInstanceTypes(families, DEFAULT_SIZE);
    }

    List<String> expandToInstanceTypes(final List<String> families, final InstanceSize size) {
        final var types = new ArrayList<String>();
        for (final String family : families) {
            for (final String awsSize : size.awsSizes()) {
                types.add(family + "." + awsSize);
            }
        }

        return types;
    }

    private List<PricedCandidate> queryContinent(final List<String> regions, final List<String> candidateTypes) {
        final var allPrices = new ArrayList<PricedCandidate>();

        for (final String region : regions) {
            try (final var ec2 = ec2Factory.apply(region)) {
                final var prices = querySpotPrices(ec2, candidateTypes, region);
                allPrices.addAll(prices);
            } catch (final Exception e) {

                final var msg = e.getMessage();
                if (!isRegionSkippable(msg)) {
                    System.err.println("  Warning: could not query spot prices in " + region + ": " + msg);
                }
            }
        }

        return allPrices;
    }

    private List<PricedCandidate> querySpotPrices(final Ec2Client ec2, final List<String> instanceTypes, final String region) {
        final var results = new ArrayList<PricedCandidate>();

        final int batchSize = 20;
        for (int i = 0; i < instanceTypes.size(); i += batchSize) {
            final var batch = instanceTypes.subList(i,
                    Math.min(i + batchSize, instanceTypes.size()));

            try {
                final var response = ec2.describeSpotPriceHistory(
                        DescribeSpotPriceHistoryRequest.builder()
                                .instanceTypesWithStrings(batch)
                                .productDescriptions("Linux/UNIX")
                                .startTime(Instant.now())
                                .maxResults(batch.size() * 3)
                                .build());

                for (final SpotPrice sp : response.spotPriceHistory()) {
                    try {
                        final double price = Double.parseDouble(sp.spotPrice());
                        results.add(new PricedCandidate(sp.instanceTypeAsString(), region, sp.availabilityZone(), price));
                    } catch (final NumberFormatException ignored) {

                    }
                }
            } catch (final Exception e) {

                if (!isRegionSkippable(e.getMessage())) {
                    System.err.println("  Warning: spot price query failed for batch in " + region + ": " + e.getMessage());
                }
            }
        }

        return results;
    }

    List<Continent> rankForeignContinents(final List<Continent> foreignContinents, final Map<Continent, List<PricedCandidate>> pricesPerContinent) {
        final var sorted = new ArrayList<>(foreignContinents);
        sorted.sort(Comparator.comparingDouble(c -> medianPrice(pricesPerContinent.getOrDefault(c, List.of()))));

        return sorted;
    }

    List<SpotRecommendation> scoreAndRank(final Map<Continent, List<PricedCandidate>> pricesPerContinent, final String preferredRegion, final Continent homeContinent,
            final List<Continent> foreignOrder) {
        final var scored = new ArrayList<ScoredCandidate>();

        for (final var entry : pricesPerContinent.entrySet()) {
            final var continent = entry.getKey();

            for (final var candidate : entry.getValue()) {
                double score = candidate.price;

                final int sizeIdx = sizeIndex(candidate.instanceType);
                score *= (1.0 - sizeIdx * SIZE_BIAS_PER_STEP);

                if (continent == homeContinent && candidate.region.equals(preferredRegion)) {

                } else if (continent == homeContinent) {
                    score *= (1.0 + TIER_1_PENALTY);
                } else {
                    score *= (1.0 + foreignPenalty(continent, foreignOrder));
                }

                scored.add(new ScoredCandidate(candidate, score));
            }
        }

        scored.sort(Comparator.comparingDouble(s -> s.score));

        final var results = new ArrayList<SpotRecommendation>();
        for (final var s : scored) {
            final var c = s.candidate;
            final var rationale = buildRationale(c, preferredRegion, homeContinent);
            results.add(new SpotRecommendation(c.instanceType, c.region, c.availabilityZone, c.price, rationale));
        }

        return results;
    }

    private double foreignPenalty(final Continent foreignContinent, final List<Continent> foreignOrder) {
        if (!foreignOrder.isEmpty() && foreignOrder.getFirst() == foreignContinent) {
            return TIER_2_PENALTY;
        }

        return TIER_3_PENALTY;
    }

    private String buildRationale(final PricedCandidate candidate, final String preferredRegion, final Continent homeContinent) {
        final var sb = new StringBuilder();
        sb.append(candidate.instanceType)
          .append(" in ")
          .append(candidate.availabilityZone)
          .append(" @ $")
          .append(String.format("%.4f", candidate.price))
          .append("/hr");

        final var candidateContinent = Continent.forRegion(candidate.region);
        if (candidateContinent != homeContinent) {
            sb.append(" (fallback continent: ")
              .append(candidateContinent.displayName())
              .append(")");
        } else if (!candidate.region.equals(preferredRegion)) {
            sb.append(" (outside preferred region ")
              .append(preferredRegion)
              .append(")");
        }

        return sb.toString();
    }

    static double medianPrice(final List<PricedCandidate> prices) {
        if (prices.isEmpty()) {
            return Double.MAX_VALUE;
        }

        final var sorted = prices.stream()
                .mapToDouble(p -> p.price)
                .sorted()
                .toArray();

        final int mid = sorted.length / 2;
        if (sorted.length % 2 == 0) {
            return (sorted[mid - 1] + sorted[mid]) / 2.0;
        }

        return sorted[mid];
    }

    private static final List<String> ALL_SIZES = List.of("large", "xlarge", "2xlarge", "4xlarge", "8xlarge", "12xlarge",
            "16xlarge");

    private int sizeIndex(final String instanceType) {
        final var parts = instanceType.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed instance type (expected family.size): " + instanceType);
        }

        final var size = parts[1];
        final int index = ALL_SIZES.indexOf(size);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown instance size '" + size + "' in " + instanceType + ". Known sizes: " + ALL_SIZES);
        }

        return index;
    }

    static boolean isRegionSkippable(final String msg) {
        if (msg == null) {
            return false;
        }

        return msg.contains("401")
                || msg.contains("AuthFailure")
                || msg.contains("OptInRequired")
                || msg.contains("timed out");
    }

    record PricedCandidate(String instanceType, String region, String availabilityZone,
                           double price) {}

    private record ScoredCandidate(PricedCandidate candidate, double score) {}
}
