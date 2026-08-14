package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionTest
{
    @Test
    void fromCodeReturnsCorrectRegion()
    {
        assertThat(Region.fromCode("eu-west-1")).isEqualTo(Region.EU_WEST_1);
        assertThat(Region.fromCode("us-east-1")).isEqualTo(Region.US_EAST_1);
        assertThat(Region.fromCode("ap-northeast-1")).isEqualTo(Region.AP_NORTHEAST_1);
    }

    @Test
    void fromCodeThrowsForUnknownRegion()
    {
        assertThatThrownBy(() -> Region.fromCode("xx-unknown-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown AWS region");
    }

    @Test
    void codeReturnsAwsRegionCode()
    {
        assertThat(Region.EU_WEST_1.code()).isEqualTo("eu-west-1");
        assertThat(Region.US_WEST_2.code()).isEqualTo("us-west-2");
    }

    @Test
    void descriptionReturnsHumanReadableLocation()
    {
        assertThat(Region.EU_WEST_1.description()).isEqualTo("Ireland");
        assertThat(Region.US_EAST_1.description()).isEqualTo("N. Virginia");
        assertThat(Region.AP_NORTHEAST_1.description()).isEqualTo("Tokyo");
        assertThat(Region.ME_SOUTH_1.description()).isEqualTo("Bahrain");
        assertThat(Region.AF_SOUTH_1.description()).isEqualTo("Cape Town");
    }

    @Test
    void isKnownReturnsTrueForValidRegions()
    {
        assertThat(Region.isKnown("eu-west-1")).isTrue();
        assertThat(Region.isKnown("us-west-2")).isTrue();
        assertThat(Region.isKnown("ap-northeast-1")).isTrue();
        assertThat(Region.isKnown("me-south-1")).isTrue();
        assertThat(Region.isKnown("af-south-1")).isTrue();
    }

    @Test
    void isKnownReturnsFalseForUnknownRegion()
    {
        assertThat(Region.isKnown("xx-unknown-1")).isFalse();
    }

    @Test
    void allRegionsHaveNonEmptyCodeAndDescription()
    {
        for (final Region region : Region.values())
        {
            assertThat(region.code())
                .as("Region %s should have a non-empty code", region.name())
                .isNotBlank();
            assertThat(region.description())
                .as("Region %s should have a non-empty description", region.name())
                .isNotBlank();
        }
    }

    @Test
    void codesAreUnique()
    {
        final var codes = java.util.Arrays.stream(Region.values())
            .map(Region::code)
            .toList();

        assertThat(codes).doesNotHaveDuplicates();
    }
}
