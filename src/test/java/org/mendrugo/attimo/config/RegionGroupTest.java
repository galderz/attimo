package org.mendrugo.attimo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionGroupTest
{
    @Test
    void europeGroupContainsIreland()
    {
        final var group = RegionGroup.forRegion("eu-west-1");
        assertThat(group).isEqualTo(RegionGroup.EUROPE);
        assertThat(group.regions()).contains(
            "eu-west-1"
            , "eu-west-2"
            , "eu-central-1"
        );
    }

    @Test
    void usEastGroupContainsBothRegions()
    {
        assertThat(RegionGroup.forRegion("us-east-1")).isEqualTo(RegionGroup.US_EAST);
        assertThat(RegionGroup.forRegion("us-east-2")).isEqualTo(RegionGroup.US_EAST);
    }

    @Test
    void apNortheastGroup()
    {
        assertThat(RegionGroup.forRegion("ap-northeast-1")).isEqualTo(RegionGroup.AP_NORTHEAST);
    }

    @Test
    void unknownRegionThrows()
    {
        assertThatThrownBy(() -> RegionGroup.forRegion("xx-unknown-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown AWS region")
            .hasMessageContaining("xx-unknown-1");
    }

    @Test
    void isKnownReturnsTrueForValidRegion()
    {
        assertThat(RegionGroup.isKnown("eu-west-1")).isTrue();
        assertThat(RegionGroup.isKnown("us-west-2")).isTrue();
    }

    @Test
    void isKnownReturnsFalseForUnknownRegion()
    {
        assertThat(RegionGroup.isKnown("xx-unknown-1")).isFalse();
    }

    @Test
    void allGroupsHaveAtLeastOneRegion()
    {
        for (final RegionGroup group : RegionGroup.values())
        {
            assertThat(group.regions())
                .as("Group %s should have regions", group.name())
                .isNotEmpty();
        }
    }
}
