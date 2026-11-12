package org.mendrugo.attimo.aws;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import org.mendrugo.attimo.isa.IsaFeature;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

public class SpotAdvisor {

    private static final InstanceSize DEFAULT_SIZE = InstanceSize.DEFAULT;

    static final double TIER_1_PENALTY = 0.10;
    static final double TIER_2_PENALTY = 0.25;
    static final double TIER_3_PENALTY = 0.40;

    private static final double SIZE_BIAS_PER_STEP = 0.03;

    private final Function<String, Ec2Client> ec2Factory;

    public SpotAdvisor(Function<String, Ec2Client> ec2Factory) {
        this.ec2Factory = ec2Factory;
    }

    public List<SpotRecommendation> recommend(IsaFeature feature, String preferredRegion) {
        return recommend(feature, preferredRegion, DEFAULT_SIZE);
    }

    public List<SpotRecommendation> recommend(IsaFeature feature, String preferredRegion, InstanceSize size) {
        Continent homeContinent = Continent.forRegion(preferredRegion);
        List<String> candidateTypes = expandToInstanceTypes(feature.families(), size);

        if (candidateTypes.isEmpty()) {
            return List.of();
        }

        HashMap<Continent, List<PricedCandidate>> pricesPerContinent = new HashMap<Continent, List<PricedCandidate>>();

        List<PricedCandidate> homePrices = queryContinent(homeContinent.regionCodes(), candidateTypes);
        pricesPerContinent.put(homeContinent, homePrices);

        for (Continent foreign : homeContinent.others()) {
            List<PricedCandidate> foreignPrices = queryContinent(foreign.representativeCodes(), candidateTypes);
            pricesPerContinent.put(foreign, foreignPrices);
        }

        List<Continent> foreignContinents = homeContinent.others();
        List<Continent> foreignOrder = rankForeignContinents(foreignContinents, pricesPerContinent);
        return scoreAndRank(pricesPerContinent, preferredRegion, homeContinent, foreignOrder);
    }

    List<String> expandToInstanceTypes(List<String> families) {
        return expandToInstanceTypes(families, DEFAULT_SIZE);
    }

    List<String> expandToInstanceTypes(List<String> families, InstanceSize size) {
        ArrayList<String> types = new ArrayList<String>();
        for (String family : families) {
            for (String awsSize : size.awsSizes()) {
                types.add(family + "." + awsSize);
            }
        }

        return types;
    }

    private List<PricedCandidate> queryContinent(List<String> regions, List<String> candidateTypes) {
        ArrayList<PricedCandidate> allPrices = new ArrayList<PricedCandidate>();

        for (String region : regions) {
            try (Ec2Client ec2 = ec2Factory.apply(region)) {
                List<PricedCandidate> prices = querySpotPrices(ec2, candidateTypes, region);
                allPrices.addAll(prices);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (!isRegionSkippable(msg)) {
                    System.err.println("  Warning: could not query spot prices in " + region + ": " + msg);
                }
            }
        }

        return allPrices;
    }

    private List<PricedCandidate> querySpotPrices(Ec2Client ec2, List<String> instanceTypes, String region) {
        ArrayList<PricedCandidate> results = new ArrayList<PricedCandidate>();

        int batchSize = 20;
        for (int i = 0; i < instanceTypes.size(); i += batchSize) {
            List<String> batch = instanceTypes.subList(i,
                    Math.min(i + batchSize, instanceTypes.size()));

            try {
                DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(
                        DescribeSpotPriceHistoryRequest.builder()
                                .instanceTypesWithStrings(batch)
                                .productDescriptions("Linux/UNIX")
                                .startTime(Instant.now())
                                .maxResults(batch.size() * 3)
                                .build());

                for (SpotPrice sp : response.spotPriceHistory()) {
                    try {
                        double price = Double.parseDouble(sp.spotPrice());
                        results.add(new PricedCandidate(sp.instanceTypeAsString(), region, sp.availabilityZone(), price));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception e) {
                if (!isRegionSkippable(e.getMessage())) {
                    System.err.println("  Warning: spot price query failed for batch in " + region + ": " + e.getMessage());
                }
            }
        }

        return results;
    }

    List<Continent> rankForeignContinents(List<Continent> foreignContinents, Map<Continent, List<PricedCandidate>> pricesPerContinent) {
        ArrayList<Continent> sorted = new ArrayList<>(foreignContinents);
        sorted.sort(Comparator.comparingDouble(c -> medianPrice(pricesPerContinent.getOrDefault(c, List.of()))));
        return sorted;
    }

    List<SpotRecommendation> scoreAndRank(Map<Continent, List<PricedCandidate>> pricesPerContinent, String preferredRegion, Continent homeContinent,
            List<Continent> foreignOrder) {
        ArrayList<ScoredCandidate> scored = new ArrayList<ScoredCandidate>();

        for (Entry<Continent, List<PricedCandidate>> entry : pricesPerContinent.entrySet()) {
            Continent continent = entry.getKey();

            for (PricedCandidate candidate : entry.getValue()) {
                double score = candidate.price;

                int sizeIdx = sizeIndex(candidate.instanceType);
                score *= (1.0 - sizeIdx * SIZE_BIAS_PER_STEP);

                if (continent == homeContinent && candidate.region.equals(preferredRegion)) {
                    // Tier 0: no penalty
                } else if (continent == homeContinent) {
                    score *= (1.0 + TIER_1_PENALTY);
                } else {
                    score *= (1.0 + foreignPenalty(continent, foreignOrder));
                }

                scored.add(new ScoredCandidate(candidate, score));
            }
        }

        scored.sort(Comparator.comparingDouble(s -> s.score));

        ArrayList<SpotRecommendation> results = new ArrayList<SpotRecommendation>();
        for (ScoredCandidate s : scored) {
            PricedCandidate c = s.candidate;
            String rationale = buildRationale(c, preferredRegion, homeContinent);
            results.add(new SpotRecommendation(c.instanceType, c.region, c.availabilityZone, c.price, rationale));
        }

        return results;
    }

    private double foreignPenalty(Continent foreignContinent, List<Continent> foreignOrder) {
        if (!foreignOrder.isEmpty() && foreignOrder.getFirst() == foreignContinent) {
            return TIER_2_PENALTY;
        }

        return TIER_3_PENALTY;
    }

    private String buildRationale(PricedCandidate candidate, String preferredRegion, Continent homeContinent) {
        StringBuilder sb = new StringBuilder();
        sb.append(candidate.instanceType)
          .append(" in ")
          .append(candidate.availabilityZone)
          .append(" @ $")
          .append(String.format("%.4f", candidate.price))
          .append("/hr");

        Continent candidateContinent = Continent.forRegion(candidate.region);
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

    static double medianPrice(List<PricedCandidate> prices) {
        if (prices.isEmpty()) {
            return Double.MAX_VALUE;
        }

        double[] sorted = prices.stream()
                                .mapToDouble(p -> p.price)
                                .sorted()
                                .toArray();

        int mid = sorted.length / 2;
        if (sorted.length % 2 == 0) {
            return (sorted[mid - 1] + sorted[mid]) / 2.0;
        }

        return sorted[mid];
    }

    private static final List<String> ALL_SIZES = List.of("large", "xlarge", "2xlarge", "4xlarge", "8xlarge", "12xlarge",
            "16xlarge");

    private int sizeIndex(String instanceType) {
        String[] parts = instanceType.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed instance type (expected family.size): " + instanceType);
        }

        String size = parts[1];
        int index = ALL_SIZES.indexOf(size);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown instance size '" + size + "' in " + instanceType + ". Known sizes: " + ALL_SIZES);
        }

        return index;
    }

    static boolean isRegionSkippable(String msg) {
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
