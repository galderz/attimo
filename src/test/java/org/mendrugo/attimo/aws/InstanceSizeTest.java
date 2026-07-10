package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceSizeTest
{
    @Test
    void defaultIsMedium()
    {
        assertThat(InstanceSize.DEFAULT).isEqualTo(InstanceSize.MEDIUM);
    }

    @Test
    void microHasSmallestSizes()
    {
        assertThat(InstanceSize.MICRO.awsSizes()).containsExactly("large", "xlarge");
    }

    @Test
    void smallHasMidSizes()
    {
        assertThat(InstanceSize.SMALL.awsSizes()).containsExactly("2xlarge", "4xlarge");
    }

    @Test
    void mediumHasLargerSizes()
    {
        assertThat(InstanceSize.MEDIUM.awsSizes()).containsExactly("4xlarge", "8xlarge");
    }

    @Test
    void largeHasLargestSizes()
    {
        assertThat(InstanceSize.LARGE.awsSizes()).containsExactly(
            "8xlarge"
            , "12xlarge"
            , "16xlarge"
        );
    }

    @Test
    void fromLabelCaseInsensitive()
    {
        assertThat(InstanceSize.fromLabel("micro")).isEqualTo(InstanceSize.MICRO);
        assertThat(InstanceSize.fromLabel("MEDIUM")).isEqualTo(InstanceSize.MEDIUM);
        assertThat(InstanceSize.fromLabel("Large")).isEqualTo(InstanceSize.LARGE);
    }

    @Test
    void fromLabelThrowsOnUnknown()
    {
        assertThatThrownBy(() -> InstanceSize.fromLabel("huge"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("huge")
            .hasMessageContaining("Valid sizes");
    }

    @Test
    void labelsMatchEnumNames()
    {
        assertThat(InstanceSize.MICRO.label()).isEqualTo("micro");
        assertThat(InstanceSize.SMALL.label()).isEqualTo("small");
        assertThat(InstanceSize.MEDIUM.label()).isEqualTo("medium");
        assertThat(InstanceSize.LARGE.label()).isEqualTo("large");
    }

    @Test
    void descriptionsAreNonEmpty()
    {
        for (final InstanceSize size : InstanceSize.values())
        {
            assertThat(size.description()).isNotBlank();
        }
    }
}
