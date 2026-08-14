package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.mendrugo.attimo.config.Continent;
import org.mendrugo.attimo.isa.IsaFeature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotAdvisorTest
{
    @Test
    void expandsFamiliesToInstanceTypesWithDefaultSize()
    {
        final var advisor = new SpotAdvisor(region -> null);
        final var types = advisor.expandToInstanceTypes(List.of("c7i", "m7i"));

        // Default size is MEDIUM: 4xlarge, 8xlarge
        assertThat(types).contains(
            "c7i.4xlarge"
            , "c7i.8xlarge"
            , "m7i.4xlarge"
            , "m7i.8xlarge"
        );
        assertThat(types).hasSize(4); // 2 families × 2 sizes
    }

    @Test
    void expandsFamiliesToInstanceTypesWithMicroSize()
    {
        final var advisor = new SpotAdvisor(region -> null);
        final var types = advisor.expandToInstanceTypes(
            List.of("c7i")
            , InstanceSize.MICRO
        );

        assertThat(types).containsExactly("c7i.large", "c7i.xlarge");
    }

    @Test
    void expandsFamiliesToInstanceTypesWithLargeSize()
    {
        final var advisor = new SpotAdvisor(region -> null);
        final var types = advisor.expandToInstanceTypes(
            List.of("c7i")
            , InstanceSize.LARGE
        );

        assertThat(types).containsExactly(
            "c7i.8xlarge"
            , "c7i.12xlarge"
            , "c7i.16xlarge"
        );
    }

    @Test
    void selectsCheapestCandidate()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1b", 0.05)
            , new SpotAdvisor.PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.15)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).isNotEmpty();
        // xlarge at $0.05 should win (with size bias boost)
        assertThat(results.getFirst().pricePerHour()).isLessThanOrEqualTo(0.10);
    }

    @Test
    void prefersPreferredRegionWhenPricesAreSimilar()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // eu-west-1 (preferred) at $0.10, eu-west-2 (same continent) at $0.095
        // The ~5% difference is within the 10% tier-1 penalty
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.095)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).isNotEmpty();
        // Preferred region should win because eu-west-2's effective score
        // is 0.095 * 1.10 = 0.1045 > 0.10
        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void prefersNonPreferredRegionWhenSignificantlyCheaper()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // eu-west-1 (preferred) at $0.20, eu-west-2 at $0.05
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.20)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.05)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().region()).isEqualTo("eu-west-2");
    }

    @Test
    void biasesTowardLargerInstances()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Same price but different sizes — larger should be preferred
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.10)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).isNotEmpty();
        // With equal price, size bias should prefer larger
        assertThat(results.getFirst().instanceType()).isEqualTo("c7i.2xlarge");
    }

    @Test
    void rationaleIncludesInstanceAndPrice()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.067)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("c7i.xlarge");
        assertThat(results.getFirst().rationale()).contains("$0.0670");
        assertThat(results.getFirst().rationale()).contains("eu-west-1a");
    }

    @Test
    void rejectsMalformedInstanceType()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i", "eu-west-1", "eu-west-1a", 0.10)
        ));

        assertThatThrownBy(() -> advisor.scoreAndRank(
            prices, "eu-west-1", Continent.EMEA, List.of()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Malformed instance type");
    }

    @Test
    void rejectsUnknownInstanceSize()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.128xlarge", "eu-west-1", "eu-west-1a", 0.10)
        ));

        assertThatThrownBy(() -> advisor.scoreAndRank(
            prices, "eu-west-1", Continent.EMEA, List.of()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown instance size")
            .hasMessageContaining("128xlarge");
    }

    @Test
    void rationaleNotesNonPreferredRegion()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-2", "eu-west-2a", 0.05)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("outside preferred region");
        assertThat(results.getFirst().rationale()).contains("eu-west-1");
    }

    // === New continent-aware tests ===

    @Test
    void returnsRankedListWithMultipleCandidates()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.08)
        ));
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        // All 3 candidates should be in the ranked list
        assertThat(results).hasSize(3);
    }

    @Test
    void foreignContinentGetsPenalized()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // EMEA at $0.08 vs Americas at $0.07
        // Americas gets +25% penalty → $0.0875 > $0.08
        // So EMEA should still win
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.08)
        ));
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.07)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void foreignContinentWinsWhenSignificantlyCheaper()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // EMEA at $0.20 vs Americas at $0.05
        // Americas penalty: $0.05 * 1.25 = $0.0625 < $0.20
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.20)
        ));
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void cheaperForeignContinentGetsTier2PenaltyNotTier3()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Americas median is cheaper than Asia-Pacific
        // Same raw price for candidates → tier 2 (25%) beats tier 3 (40%)
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)
        ));

        // Americas is cheaper by median (same here but ranked first by order)
        final var foreignOrder = advisor.rankForeignContinents(
            List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
            , prices
        );

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , foreignOrder
        );

        // Americas candidate (tier 2, +25%) should beat Asia-Pacific (tier 3, +40%)
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
        assertThat(results.get(1).region()).isEqualTo("ap-northeast-1");
    }

    @Test
    void rationaleNotesFallbackContinent()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.xlarge", "us-east-1", "us-east-1a", 0.05)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("fallback continent");
        assertThat(results.getFirst().rationale()).contains("Americas");
    }

    @Test
    void emptyResultWhenNoCandidatesAnywhere()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of());
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        assertThat(results).isEmpty();
    }

    @Test
    void medianPriceCalculation()
    {
        // Odd number
        assertThat(SpotAdvisor.medianPrice(List.of(
            new SpotAdvisor.PricedCandidate("", "", "", 0.05)
            , new SpotAdvisor.PricedCandidate("", "", "", 0.10)
            , new SpotAdvisor.PricedCandidate("", "", "", 0.20)
        ))).isEqualTo(0.10);

        // Even number
        assertThat(SpotAdvisor.medianPrice(List.of(
            new SpotAdvisor.PricedCandidate("", "", "", 0.05)
            , new SpotAdvisor.PricedCandidate("", "", "", 0.10)
        ))).isCloseTo(0.075, org.assertj.core.data.Offset.offset(0.0001));

        // Empty list
        assertThat(SpotAdvisor.medianPrice(List.of())).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void rankForeignContinentsByCheapest()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("", "us-east-1", "", 0.15)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of(
            new SpotAdvisor.PricedCandidate("", "ap-northeast-1", "", 0.05)
        ));

        final var ranked = advisor.rankForeignContinents(
            List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
            , prices
        );

        // Asia-Pacific is cheaper, should be first
        assertThat(ranked.getFirst()).isEqualTo(Continent.ASIA_PACIFIC);
        assertThat(ranked.get(1)).isEqualTo(Continent.AMERICAS);
    }

    @Test
    void rankPutsEmptyContinentLast()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("", "us-east-1", "", 0.10)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var ranked = advisor.rankForeignContinents(
            List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
            , prices
        );

        assertThat(ranked.getFirst()).isEqualTo(Continent.AMERICAS);
    }

    @Test
    void fallbackWorksWhenHomeContinentHasNoCandidates()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // No EMEA capacity — only Americas has candidates
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        // Should find the Americas candidate
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
        assertThat(results.getFirst().rationale()).contains("fallback continent");
    }

    // === Tier penalty verification tests ===

    @Test
    void preferredRegionGetsZeroPenalty()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Single candidate in preferred region — score should equal
        // raw price adjusted only by size bias, no proximity penalty
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).hasSize(1);
        // large is sizeIndex 0 → no size bias. Score = 0.10 * (1.0 - 0*0.03) = 0.10
        assertThat(results.getFirst().pricePerHour()).isEqualTo(0.10);
    }

    @Test
    void sameContinentNonPreferredGetsTier1Penalty()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Two candidates: preferred at $0.10, non-preferred at $0.092
        // Non-preferred effective: $0.092 * 1.10 = $0.1012 > $0.10
        // So preferred region should win
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.092)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void sameContinentNonPreferredWinsWhenCheapEnough()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Non-preferred at $0.080, effective: $0.080 * 1.10 = $0.088 < $0.10
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.080)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-central-1");
    }

    @Test
    void emptyForeignOrderGivesTier3ToAllForeignCandidates()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Home at $0.10, foreign at $0.075
        // With empty foreignOrder, foreign gets tier 3 (+40%): $0.075 * 1.40 = $0.105 > $0.10
        // So home should win
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
        ));
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.075)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()  // empty foreignOrder
        );

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void emptyForeignOrderForeignStillWinsWhenMuchCheaper()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Foreign at $0.03, even with tier 3 (+40%): $0.03 * 1.40 = $0.042 < $0.10
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
        ));
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.03)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()  // empty foreignOrder
        );

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void allThreeContinentsCorrectTierAssignment()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Same raw price everywhere — tiers should determine ranking
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.10)
        ));
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)
        ));

        // Americas cheaper → tier 2, Asia-Pacific → tier 3
        final var foreignOrder = List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC);

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , foreignOrder
        );

        assertThat(results).hasSize(4);
        // Tier 0 (preferred, 0%): eu-west-1 → score 0.10
        assertThat(results.get(0).region()).isEqualTo("eu-west-1");
        // Tier 1 (same continent, +10%): eu-central-1 → score 0.11
        assertThat(results.get(1).region()).isEqualTo("eu-central-1");
        // Tier 2 (cheaper foreign, +25%): us-east-1 → score 0.125
        assertThat(results.get(2).region()).isEqualTo("us-east-1");
        // Tier 3 (expensive foreign, +40%): ap-northeast-1 → score 0.14
        assertThat(results.get(3).region()).isEqualTo("ap-northeast-1");
    }

    @Test
    void bothForeignContinentsSameMedianPriceFirstGetsTier2()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Both foreign continents have identical median price
        // rankForeignContinents preserves input order for equal medians
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)
        ));

        final var foreignOrder = advisor.rankForeignContinents(
            List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
            , prices
        );

        // Same median → stable sort preserves input order
        // Americas first → gets tier 2
        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , foreignOrder
        );

        assertThat(results).hasSize(2);
        // Americas (tier 2, +25%) → 0.125 < Asia-Pacific (tier 3, +40%) → 0.14
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void onlyOneForeignContinentHasDataOtherIsEmpty()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var foreignOrder = advisor.rankForeignContinents(
            List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
            , prices
        );

        // Americas has data → ranked first → gets tier 2
        assertThat(foreignOrder.getFirst()).isEqualTo(Continent.AMERICAS);

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , foreignOrder
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void homeContinentOnlyPreferredRegionAllTier0()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Multiple candidates all in the preferred region
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1b", 0.12)
            , new SpotAdvisor.PricedCandidate("m7i.large", "eu-west-1", "eu-west-1c", 0.11)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of()
        );

        assertThat(results).hasSize(3);
        // All in preferred region — no proximity penalty applied
        // Ranking should be purely by price (with size bias)
        for (final var r : results)
        {
            assertThat(r.region()).isEqualTo("eu-west-1");
        }
    }

    @Test
    void multipleCandidatesInSameForeignContinentGetSameTier()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Two candidates in Americas (tier 2), same type/size
        // Both should get the same penalty — cheaper raw price wins
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.08)
            , new SpotAdvisor.PricedCandidate("c7i.large", "us-west-2", "us-west-2a", 0.06)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        assertThat(results).hasSize(2);
        // Both tier 2: us-west-2 ($0.06*1.25=0.075) < us-east-1 ($0.08*1.25=0.10)
        assertThat(results.getFirst().region()).isEqualTo("us-west-2");
        assertThat(results.get(1).region()).isEqualTo("us-east-1");
    }

    @Test
    void tier2ForeignBeatsExpensiveHomeContinent()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Home very expensive, tier 2 foreign much cheaper
        // Even with +25% penalty, foreign wins
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.50)
        ));
        prices.put(Continent.AMERICAS, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)
        );

        // us-east-1: $0.05 * 1.25 = $0.0625 << $0.50
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void tier3ForeignBeatsExpensiveHomeContinent()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Home very expensive, only tier 3 foreign available
        final var prices = new HashMap<Continent, List<SpotAdvisor.PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.50)
        ));
        prices.put(Continent.ASIA_PACIFIC, List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.05)
        ));

        final var results = advisor.scoreAndRank(
            prices
            , "eu-west-1"
            , Continent.EMEA
            , List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC)  // Americas first but empty
        );

        // ap-northeast-1: $0.05 * 1.40 = $0.07 << $0.50
        assertThat(results.getFirst().region()).isEqualTo("ap-northeast-1");
    }
}
