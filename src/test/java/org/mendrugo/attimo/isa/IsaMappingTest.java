package org.mendrugo.attimo.isa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsaMappingTest
{
    private final IsaMapping mapping = new IsaMapping();

    @Test
    void resolvesAvx512ToX86Families()
    {
        final var feature = mapping.resolve("avx512");
        assertThat(feature).isNotNull();
        assertThat(feature.architecture()).isEqualTo("x86_64");
        assertThat(feature.families()).contains("c5", "c6i", "c7i", "m5");
        assertThat(feature.description()).isNotBlank();
    }

    @Test
    void resolvesAvx512VnniToSubsetOfFamilies()
    {
        final var feature = mapping.resolve("avx512_vnni");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c6i", "c7i");
        // c5 does NOT have VNNI
        assertThat(feature.families()).doesNotContain("c5");
    }

    @Test
    void resolvesSveToGraviton3Families()
    {
        final var feature = mapping.resolve("sve");
        assertThat(feature).isNotNull();
        assertThat(feature.architecture()).isEqualTo("aarch64");
        assertThat(feature.families()).contains("c7g", "m7g", "r7g");
        // Graviton2 (c6g) does NOT have SVE
        assertThat(feature.families()).doesNotContain("c6g");
    }

    @Test
    void resolvesSve2ToGraviton4Families()
    {
        final var feature = mapping.resolve("sve2");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c8g", "m8g");
        // Graviton3 (c7g) does NOT have SVE2
        assertThat(feature.families()).doesNotContain("c7g");
    }

    @Test
    void resolvesAmxToSapphireRapidsFamilies()
    {
        final var feature = mapping.resolve("amx");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c7i", "m7i");
        assertThat(feature.families()).doesNotContain("c6i");
    }

    @Test
    void caseInsensitiveLookup()
    {
        assertThat(mapping.resolve("AVX512")).isNotNull();
        assertThat(mapping.resolve("Sve")).isNotNull();
        assertThat(mapping.resolve("NEON")).isNotNull();
    }

    @Test
    void unknownFeatureReturnsNull()
    {
        assertThat(mapping.resolve("nonexistent_feature")).isNull();
    }

    @Test
    void allFeatureNamesNotEmpty()
    {
        final var names = mapping.allFeatureNames();
        assertThat(names).isNotEmpty();
        assertThat(names).contains("avx512", "sve", "neon", "amx");
    }

    @Test
    void allFeaturesMatchAllNames()
    {
        assertThat(mapping.allFeatures()).hasSameSizeAs(mapping.allFeatureNames());
    }

    @Test
    void neonIncludesAllGravitonGenerations()
    {
        final var feature = mapping.resolve("neon");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c6g", "c7g", "c8g");
    }
}
