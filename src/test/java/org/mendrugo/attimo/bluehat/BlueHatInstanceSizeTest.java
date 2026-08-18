package org.mendrugo.attimo.bluehat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlueHatInstanceSizeTest
{
    @ParameterizedTest
    @CsvSource({
        "micro, 1, 2"
        , "small, 8, 16"
        , "medium, 16, 32"
        , "large, 32, 64"
    })
    void mapsSizeToCpuAndMemory(
        final String label
        , final int expectedCpus
        , final int expectedMemory
    )
    {
        final var size = BlueHatInstanceSize.fromLabel(label);
        assertThat(size.cpus()).isEqualTo(expectedCpus);
        assertThat(size.memoryGb()).isEqualTo(expectedMemory);
    }

    @ParameterizedTest
    @CsvSource({
        "MICRO, micro"
        , "Small, small"
        , "MEDIUM, medium"
        , "Large, large"
    })
    void fromLabelIsCaseInsensitive(
        final String input
        , final String expectedLabel
    )
    {
        final var size = BlueHatInstanceSize.fromLabel(input);
        assertThat(size.label()).isEqualTo(expectedLabel);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tiny", "huge", "xl", ""})
    void fromLabelRejectsInvalidLabels(final String input)
    {
        assertThatThrownBy(() -> BlueHatInstanceSize.fromLabel(input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown instance size");
    }

    @Test
    void defaultIsMedium()
    {
        assertThat(BlueHatInstanceSize.DEFAULT).isEqualTo(BlueHatInstanceSize.MEDIUM);
    }

    @Test
    void memoryToCpuRatioIsConsistentForDevelopment()
    {
        // For OpenJDK development, memory should be at least 2x CPUs
        for (final BlueHatInstanceSize size : BlueHatInstanceSize.values())
        {
            assertThat(size.memoryGb())
                .as("Size %s should have memory >= 2x CPUs", size.label())
                .isGreaterThanOrEqualTo(size.cpus() * 2);
        }
    }

    @Test
    void allSizesHaveDescriptions()
    {
        for (final BlueHatInstanceSize size : BlueHatInstanceSize.values())
        {
            assertThat(size.description())
                .as("Size %s should have a description", size.label())
                .isNotBlank();
        }
    }

    @Test
    void cpusAreStrictlyIncreasing()
    {
        final var values = BlueHatInstanceSize.values();
        for (int i = 1; i < values.length; i++)
        {
            assertThat(values[i].cpus())
                .as("%s should have more CPUs than %s"
                    , values[i].label(), values[i - 1].label())
                .isGreaterThan(values[i - 1].cpus());
        }
    }
}
