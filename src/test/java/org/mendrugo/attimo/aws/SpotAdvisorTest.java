package org.mendrugo.attimo.aws;

import java.util.HashMap;
import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.mendrugo.attimo.aws.SpotAdvisor.PricedCandidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotAdvisorTest {
    @Test
    void expandsFamiliesToInstanceTypesWithDefaultSize() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);
        List<String> types = advisor.expandToInstanceTypes(List.of("c7i", "m7i"));

        assertThat(types).contains("c7i.4xlarge", "c7i.8xlarge", "m7i.4xlarge", "m7i.8xlarge");
        assertThat(types).hasSize(4);
    }

    @Test
    void expandsFamiliesToInstanceTypesWithMicroSize() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);
        List<String> types = advisor.expandToInstanceTypes(List.of("c7i"), InstanceSize.MICRO);

        assertThat(types).containsExactly("c7i.large", "c7i.xlarge");
    }

    @Test
    void expandsFamiliesToInstanceTypesWithLargeSize() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);
        List<String> types = advisor.expandToInstanceTypes(List.of("c7i"), InstanceSize.LARGE);

        assertThat(types).containsExactly("c7i.8xlarge", "c7i.12xlarge", "c7i.16xlarge");
    }

    @Test
    void selectsCheapestCandidate() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(
                Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1b", 0.05), new PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.15)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();

        assertThat(results.getFirst().pricePerHour()).isLessThanOrEqualTo(0.10);
    }

    @Test
    void prefersPreferredRegionWhenPricesAreSimilar() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.095)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();

        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void prefersNonPreferredRegionWhenSignificantlyCheaper() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.20), new PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.05)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().region()).isEqualTo("eu-west-2");
    }

    @Test
    void biasesTowardLargerInstances() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(
                Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.10)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();

        assertThat(results.getFirst().instanceType()).isEqualTo("c7i.2xlarge");
    }

    @Test
    void rationaleIncludesInstanceAndPrice() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.067)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("c7i.xlarge");
        assertThat(results.getFirst().rationale()).contains("$0.0670");
        assertThat(results.getFirst().rationale()).contains("eu-west-1a");
    }

    @Test
    void rejectsMalformedInstanceType() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i", "eu-west-1", "eu-west-1a", 0.10)));

        assertThatThrownBy(() -> advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed instance type");
    }

    @Test
    void rejectsUnknownInstanceSize() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.128xlarge", "eu-west-1", "eu-west-1a", 0.10)));

        assertThatThrownBy(() -> advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown instance size")
                .hasMessageContaining("128xlarge");
    }

    @Test
    void rationaleNotesNonPreferredRegion() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.xlarge", "eu-west-2", "eu-west-2a", 0.05)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("outside preferred region");
        assertThat(results.getFirst().rationale()).contains("eu-west-1");
    }

    @Test
    void returnsRankedListWithMultipleCandidates() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.08)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(3);
    }

    @Test
    void foreignContinentGetsPenalized() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.08)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.07)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void foreignContinentWinsWhenSignificantlyCheaper() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.20)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void cheaperForeignContinentGetsTier2PenaltyNotTier3() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)));

        List<Continent> foreignOrder = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
        assertThat(results.get(1).region()).isEqualTo("ap-northeast-1");
    }

    @Test
    void rationaleNotesFallbackContinent() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.xlarge", "us-east-1", "us-east-1a", 0.05)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("fallback continent");
        assertThat(results.getFirst().rationale()).contains("Americas");
    }

    @Test
    void emptyResultWhenNoCandidatesAnywhere() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of());
        prices.put(Continent.ASIA_PACIFIC, List.of());

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).isEmpty();
    }

    @Test
    void medianPriceCalculation() {
        assertThat(SpotAdvisor.medianPrice(List.of(new PricedCandidate("", "", "", 0.05),
                new PricedCandidate("", "", "", 0.10), new PricedCandidate("", "", "", 0.20))))
                .isEqualTo(0.10);

        assertThat(SpotAdvisor.medianPrice(List.of(new PricedCandidate("", "", "", 0.05), new PricedCandidate("", "", "", 0.10)))).isCloseTo(0.075, Offset.offset(0.0001));

        assertThat(SpotAdvisor.medianPrice(List.of())).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void rankForeignContinentsByCheapest() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("", "us-east-1", "", 0.15)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("", "ap-northeast-1", "", 0.05)));

        List<Continent> ranked = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        assertThat(ranked.getFirst()).isEqualTo(Continent.ASIA_PACIFIC);
        assertThat(ranked.get(1)).isEqualTo(Continent.AMERICAS);
    }

    @Test
    void rankPutsEmptyContinentLast() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("", "us-east-1", "", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        List<Continent> ranked = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        assertThat(ranked.getFirst()).isEqualTo(Continent.AMERICAS);
    }

    @Test
    void fallbackWorksWhenHomeContinentHasNoCandidates() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
        assertThat(results.getFirst().rationale()).contains("fallback continent");
    }

    @Test
    void preferredRegionGetsZeroPenalty() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(1);

        assertThat(results.getFirst().pricePerHour()).isEqualTo(0.10);
    }

    @Test
    void sameContinentNonPreferredGetsTier1Penalty() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.092)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void sameContinentNonPreferredWinsWhenCheapEnough() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.080)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-central-1");
    }

    @Test
    void emptyForeignOrderGivesTier3ToAllForeignCandidates() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.075)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void emptyForeignOrderForeignStillWinsWhenMuchCheaper() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.03)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void allThreeContinentsCorrectTierAssignment() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.10)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)));

        List<Continent> foreignOrder = List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC);

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(4);

        assertThat(results.get(0).region()).isEqualTo("eu-west-1");

        assertThat(results.get(1).region()).isEqualTo("eu-central-1");

        assertThat(results.get(2).region()).isEqualTo("us-east-1");

        assertThat(results.get(3).region()).isEqualTo("ap-northeast-1");
    }

    @Test
    void bothForeignContinentsSameMedianPriceFirstGetsTier2() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)));

        List<Continent> foreignOrder = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(2);

        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void onlyOneForeignContinentHasDataOtherIsEmpty() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        List<Continent> foreignOrder = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        assertThat(foreignOrder.getFirst()).isEqualTo(Continent.AMERICAS);

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void homeContinentOnlyPreferredRegionAllTier0() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(
                Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1b", 0.12), new PricedCandidate("m7i.large", "eu-west-1", "eu-west-1c", 0.11)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(3);

        for (SpotRecommendation r : results) {
            assertThat(r.region()).isEqualTo("eu-west-1");
        }
    }

    @Test
    void multipleCandidatesInSameForeignContinentGetSameTier() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS,
                List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.08), new PricedCandidate("c7i.large", "us-west-2", "us-west-2a", 0.06)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(2);

        assertThat(results.getFirst().region()).isEqualTo("us-west-2");
        assertThat(results.get(1).region()).isEqualTo("us-east-1");
    }

    @Test
    void tier2ForeignBeatsExpensiveHomeContinent() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.50)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void tier3ForeignBeatsExpensiveHomeContinent() {
        SpotAdvisor advisor = new SpotAdvisor(region -> null);

        HashMap<Continent, List<PricedCandidate>> prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.50)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.05)));

        List<SpotRecommendation> results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results.getFirst().region()).isEqualTo("ap-northeast-1");
    }

    @Test
    void skipsOptInRegion401() {
        assertThat(SpotAdvisor.isRegionSkippable("(Service: Ec2, Status Code: 401, Request ID: abc)")).isTrue();
    }

    @Test
    void skipsAuthFailure() {
        assertThat(SpotAdvisor.isRegionSkippable("AuthFailure: not authorized")).isTrue();
    }

    @Test
    void skipsOptInRequired() {
        assertThat(SpotAdvisor.isRegionSkippable("OptInRequired: region not enabled")).isTrue();
    }

    @Test
    void skipsConnectTimeout() {
        assertThat(SpotAdvisor.isRegionSkippable("Unable to execute HTTP request: Connect timed out (SDK Attempt Count: 4)")).isTrue();
    }

    @Test
    void doesNotSkipRealError() {
        assertThat(SpotAdvisor.isRegionSkippable("Rate limit exceeded")).isFalse();
    }

    @Test
    void doesNotSkipConnectionRefused() {
        assertThat(SpotAdvisor.isRegionSkippable("Failed to Connect to endpoint: Connect to ec2.us-east-1.amazonaws.com failed")).isFalse();
    }

    @Test
    void doesNotSkipNull() {
        assertThat(SpotAdvisor.isRegionSkippable(null)).isFalse();
    }
}
