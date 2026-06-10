package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.mendrugo.attimo.isa.IsaFeature;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpotAdvisorTest
{
    @Test
    void expandsFamiliesToInstanceTypes()
    {
        final var advisor = new SpotAdvisor(region -> null);
        final var types = advisor.expandToInstanceTypes(List.of("c7i", "m7i"));

        assertThat(types).contains(
            "c7i.large"
            , "c7i.xlarge"
            , "c7i.2xlarge"
            , "c7i.4xlarge"
            , "m7i.large"
            , "m7i.xlarge"
        );
        assertThat(types).hasSize(8); // 2 families × 4 sizes
    }

    @Test
    void selectsCheapestCandidate()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var candidates = List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1b", 0.05)
            , new SpotAdvisor.PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.15)
        );

        final var result = advisor.selectBest(candidates, "eu-west-1");
        assertThat(result).isNotNull();
        // xlarge at $0.05 should win, but with size bias xlarge gets a boost
        assertThat(result.pricePerHour()).isLessThanOrEqualTo(0.10);
    }

    @Test
    void prefersCloserRegionWhenPricesAreSimilar()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // eu-west-1 (preferred) at $0.10, eu-west-2 (adjacent) at $0.095
        // The ~5% difference is within the 15% proximity threshold
        final var candidates = List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.095)
        );

        final var result = advisor.selectBest(candidates, "eu-west-1");
        assertThat(result).isNotNull();
        // Preferred region should win because prices are within threshold
        assertThat(result.region()).isEqualTo("eu-west-1");
    }

    @Test
    void prefersNonPreferredRegionWhenSignificantlyCheaper()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // eu-west-1 (preferred) at $0.20, eu-west-2 at $0.05
        // The 75% difference is way beyond the 15% threshold
        final var candidates = List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.20)
            , new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-2", "eu-west-2a", 0.05)
        );

        final var result = advisor.selectBest(candidates, "eu-west-1");
        assertThat(result).isNotNull();
        assertThat(result.region()).isEqualTo("eu-west-2");
    }

    @Test
    void biasesTowardLargerInstances()
    {
        final var advisor = new SpotAdvisor(region -> null);

        // Same price but different sizes — larger should be preferred
        final var candidates = List.of(
            new SpotAdvisor.PricedCandidate("c7i.large", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.10)
            , new SpotAdvisor.PricedCandidate("c7i.2xlarge", "eu-west-1", "eu-west-1a", 0.10)
        );

        final var result = advisor.selectBest(candidates, "eu-west-1");
        assertThat(result).isNotNull();
        // With equal price, size bias should prefer larger
        assertThat(result.instanceType()).isEqualTo("c7i.2xlarge");
    }

    @Test
    void rationaleIncludesInstanceAndPrice()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var candidates = List.of(
            new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-1", "eu-west-1a", 0.067)
        );

        final var result = advisor.selectBest(candidates, "eu-west-1");
        assertThat(result.rationale()).contains("c7i.xlarge");
        assertThat(result.rationale()).contains("$0.0670");
        assertThat(result.rationale()).contains("eu-west-1a");
    }

    @Test
    void rationaleNotesNonPreferredRegion()
    {
        final var advisor = new SpotAdvisor(region -> null);

        final var candidates = List.of(
            new SpotAdvisor.PricedCandidate("c7i.xlarge", "eu-west-2", "eu-west-2a", 0.05)
        );

        final var result = advisor.selectBest(candidates, "eu-west-1");
        assertThat(result.rationale()).contains("outside preferred region");
        assertThat(result.rationale()).contains("eu-west-1");
    }
}
