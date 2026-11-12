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
        final var advisor = new SpotAdvisor(region -> null);
        final var types = advisor.expandToInstanceTypes(List.of("c7i", "m7i"));

        assertThat(types).contains("c7i.4xlarge", "c7i.8xlarge", "m7i.4xlarge", "m7i.8xlarge");
        assertThat(types).hasSize(4);
    }

    @Test
    void expandsFamiliesToInstanceTypesWithMicroSize() {
        final var advisor = new SpotAdvisor(region -> null);
        final var types = advisor.expandToInstanceTypes(List.of("c7i"), InstanceSize.MICRO);

        assertThat(types).containsExactly("c7i.large", "c7i.xlarge");
    }

    @Test
    void expandsFamiliesToInstanceTypesWithLargeSize() {
        final var advisor = new SpotAdvisor(region -> null);
        final var types = advisor.expandToInstanceTypes(List.of("c7i"), InstanceSize.LARGE);

        assertThat(types).containsExactly("c7i.8xlarge", "c7i.12xlarge", "c7i.16xlarge");
    }

    @Test
    void selectsCheapestCandidate() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(
                Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1b", 0.05), new PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.15)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();

        assertThat(results.getFirst().pricePerHour()).isLessThanOrEqualTo(0.10);
    }

    @Test
    void prefersPreferredRegionWhenPricesAreSimilar() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.095)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();

        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void prefersNonPreferredRegionWhenSignificantlyCheaper() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.20), new PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.05)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().region()).isEqualTo("eu-west-2");
    }

    @Test
    void biasesTowardLargerInstances() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(
                Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.10)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).isNotEmpty();

        assertThat(results.getFirst().instanceType()).isEqualTo("c7i.2xlarge");
    }

    @Test
    void rationaleIncludesInstanceAndPrice() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.067)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("c7i.xlarge");
        assertThat(results.getFirst().rationale()).contains("$0.0670");
        assertThat(results.getFirst().rationale()).contains("eu-west-1a");
    }

    @Test
    void rejectsMalformedInstanceType() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i", "eu-west-1", "eu-west-1a", 0.10)));

        assertThatThrownBy(() -> advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed instance type");
    }

    @Test
    void rejectsUnknownInstanceSize() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.128xlarge", "eu-west-1", "eu-west-1a", 0.10)));

        assertThatThrownBy(() -> advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown instance size")
                .hasMessageContaining("128xlarge");
    }

    @Test
    void rationaleNotesNonPreferredRegion() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.xlarge", "eu-west-2", "eu-west-2a", 0.05)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("outside preferred region");
        assertThat(results.getFirst().rationale()).contains("eu-west-1");
    }

    @Test
    void returnsRankedListWithMultipleCandidates() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.08)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(3);
    }

    @Test
    void foreignContinentGetsPenalized() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.08)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.07)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void foreignContinentWinsWhenSignificantlyCheaper() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.20)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void cheaperForeignContinentGetsTier2PenaltyNotTier3() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)));

        final var foreignOrder = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
        assertThat(results.get(1).region()).isEqualTo("ap-northeast-1");
    }

    @Test
    void rationaleNotesFallbackContinent() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.xlarge", "us-east-1", "us-east-1a", 0.05)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rationale()).contains("fallback continent");
        assertThat(results.getFirst().rationale()).contains("Americas");
    }

    @Test
    void emptyResultWhenNoCandidatesAnywhere() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of());
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

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
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("", "us-east-1", "", 0.15)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("", "ap-northeast-1", "", 0.05)));

        final var ranked = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        assertThat(ranked.getFirst()).isEqualTo(Continent.ASIA_PACIFIC);
        assertThat(ranked.get(1)).isEqualTo(Continent.AMERICAS);
    }

    @Test
    void rankPutsEmptyContinentLast() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("", "us-east-1", "", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var ranked = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        assertThat(ranked.getFirst()).isEqualTo(Continent.AMERICAS);
    }

    @Test
    void fallbackWorksWhenHomeContinentHasNoCandidates() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
        assertThat(results.getFirst().rationale()).contains("fallback continent");
    }

    @Test
    void preferredRegionGetsZeroPenalty() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(1);

        assertThat(results.getFirst().pricePerHour()).isEqualTo(0.10);
    }

    @Test
    void sameContinentNonPreferredGetsTier1Penalty() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.092)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void sameContinentNonPreferredWinsWhenCheapEnough() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.080)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-central-1");
    }

    @Test
    void emptyForeignOrderGivesTier3ToAllForeignCandidates() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.075)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("eu-west-1");
    }

    @Test
    void emptyForeignOrderForeignStillWinsWhenMuchCheaper() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.03)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void allThreeContinentsCorrectTierAssignment() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.large", "eu-central-1", "eu-central-1a", 0.10)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)));

        final var foreignOrder = List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC);

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(4);

        assertThat(results.get(0).region()).isEqualTo("eu-west-1");

        assertThat(results.get(1).region()).isEqualTo("eu-central-1");

        assertThat(results.get(2).region()).isEqualTo("us-east-1");

        assertThat(results.get(3).region()).isEqualTo("ap-northeast-1");
    }

    @Test
    void bothForeignContinentsSameMedianPriceFirstGetsTier2() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.10)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.10)));

        final var foreignOrder = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(2);

        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void onlyOneForeignContinentHasDataOtherIsEmpty() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));
        prices.put(Continent.ASIA_PACIFIC, List.of());

        final var foreignOrder = advisor.rankForeignContinents(List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC), prices);

        assertThat(foreignOrder.getFirst()).isEqualTo(Continent.AMERICAS);

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, foreignOrder);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void homeContinentOnlyPreferredRegionAllTier0() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(
                Continent.EMEA,
                List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10), new PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1b", 0.12), new PricedCandidate("m7i.large", "eu-west-1", "eu-west-1c", 0.11)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of());

        assertThat(results).hasSize(3);

        for (final var r : results) {
            assertThat(r.region()).isEqualTo("eu-west-1");
        }
    }

    @Test
    void multipleCandidatesInSameForeignContinentGetSameTier() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of());
        prices.put(Continent.AMERICAS,
                List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.08), new PricedCandidate("c7i.large", "us-west-2", "us-west-2a", 0.06)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results).hasSize(2);

        assertThat(results.getFirst().region()).isEqualTo("us-west-2");
        assertThat(results.get(1).region()).isEqualTo("us-east-1");
    }

    @Test
    void tier2ForeignBeatsExpensiveHomeContinent() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.50)));
        prices.put(Continent.AMERICAS, List.of(new PricedCandidate("c7i.large", "us-east-1", "us-east-1a", 0.05)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

        assertThat(results.getFirst().region()).isEqualTo("us-east-1");
    }

    @Test
    void tier3ForeignBeatsExpensiveHomeContinent() {
        final var advisor = new SpotAdvisor(region -> null);

        final var prices = new HashMap<Continent, List<PricedCandidate>>();
        prices.put(Continent.EMEA, List.of(new PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.50)));
        prices.put(Continent.ASIA_PACIFIC, List.of(new PricedCandidate("c7i.large", "ap-northeast-1", "ap-northeast-1a", 0.05)));

        final var results = advisor.scoreAndRank(prices, "eu-west-1", Continent.EMEA, List.of(Continent.AMERICAS, Continent.ASIA_PACIFIC));

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
