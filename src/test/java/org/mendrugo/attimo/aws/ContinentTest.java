package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContinentTest
{
    @Test
    void emeaContainsEuropeanRegions()
    {
        final var continent = Continent.forRegion("eu-west-1");
        assertThat(continent).isEqualTo(Continent.EMEA);
        assertThat(continent.regions()).contains(
            Region.EU_WEST_1
            , Region.EU_WEST_2
            , Region.EU_CENTRAL_1
        );
    }

    @Test
    void emeaContainsMiddleEast()
    {
        assertThat(Continent.forRegion("me-south-1")).isEqualTo(Continent.EMEA);
        assertThat(Continent.forRegion("me-central-1")).isEqualTo(Continent.EMEA);
    }

    @Test
    void emeaContainsAfrica()
    {
        assertThat(Continent.forRegion("af-south-1")).isEqualTo(Continent.EMEA);
    }

    @Test
    void americasContainsUsCanadaSouthAmerica()
    {
        assertThat(Continent.forRegion("us-east-1")).isEqualTo(Continent.AMERICAS);
        assertThat(Continent.forRegion("us-west-2")).isEqualTo(Continent.AMERICAS);
        assertThat(Continent.forRegion("ca-central-1")).isEqualTo(Continent.AMERICAS);
        assertThat(Continent.forRegion("sa-east-1")).isEqualTo(Continent.AMERICAS);
    }

    @Test
    void asiaPacificContainsApRegions()
    {
        assertThat(Continent.forRegion("ap-northeast-1")).isEqualTo(Continent.ASIA_PACIFIC);
        assertThat(Continent.forRegion("ap-southeast-1")).isEqualTo(Continent.ASIA_PACIFIC);
        assertThat(Continent.forRegion("ap-south-1")).isEqualTo(Continent.ASIA_PACIFIC);
    }

    @Test
    void unknownRegionThrows()
    {
        assertThatThrownBy(() -> Continent.forRegion("xx-unknown-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown AWS region")
            .hasMessageContaining("xx-unknown-1");
    }

    @Test
    void allContinentRegionsAreKnownInRegionEnum()
    {
        for (final Continent continent : Continent.values())
        {
            for (final Region region : continent.regions())
            {
                assertThat(Region.isKnown(region.code()))
                    .as("%s should be a known region", region.code())
                    .isTrue();
            }
        }
    }

    @Test
    void allContinentsHaveAtLeastOneRegion()
    {
        for (final Continent continent : Continent.values())
        {
            assertThat(continent.regions())
                .as("Continent %s should have regions", continent.name())
                .isNotEmpty();
        }
    }

    @Test
    void allContinentsHaveThreeRepresentatives()
    {
        for (final Continent continent : Continent.values())
        {
            assertThat(continent.representatives())
                .as("Continent %s should have 3 representatives", continent.name())
                .hasSize(3);
        }
    }

    @Test
    void representativesAreSubsetOfRegions()
    {
        for (final Continent continent : Continent.values())
        {
            assertThat(continent.regions())
                .as("Representatives of %s should be within its regions", continent.name())
                .containsAll(continent.representatives());
        }
    }

    @Test
    void othersReturnsTwoOtherContinents()
    {
        final var emeaOthers = Continent.EMEA.others();
        assertThat(emeaOthers).containsExactly(Continent.AMERICAS, Continent.ASIA_PACIFIC);

        final var americasOthers = Continent.AMERICAS.others();
        assertThat(americasOthers).containsExactly(Continent.EMEA, Continent.ASIA_PACIFIC);

        final var apOthers = Continent.ASIA_PACIFIC.others();
        assertThat(apOthers).containsExactly(Continent.EMEA, Continent.AMERICAS);
    }

    @Test
    void displayNameIsHumanReadable()
    {
        assertThat(Continent.EMEA.displayName()).contains("Europe");
        assertThat(Continent.AMERICAS.displayName()).contains("Americas");
        assertThat(Continent.ASIA_PACIFIC.displayName()).contains("Asia");
    }

    @Test
    void exactlyThreeContinents()
    {
        assertThat(Continent.values()).hasSize(3);
    }
}
