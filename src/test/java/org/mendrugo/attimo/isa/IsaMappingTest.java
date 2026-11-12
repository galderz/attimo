package org.mendrugo.attimo.isa;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsaMappingTest {
    private final IsaMapping mapping = new IsaMapping();

    @Test
    void resolvesAvx512ToX86Families() {
        IsaFeature feature = mapping.resolve("avx512");
        assertThat(feature).isNotNull();
        assertThat(feature.architecture()).isEqualTo("x86_64");
        assertThat(feature.families()).contains("c5", "c6i", "c7i", "m5");
        assertThat(feature.description()).isNotBlank();
    }

    @Test
    void resolvesAvx512VnniToSubsetOfFamilies() {
        IsaFeature feature = mapping.resolve("avx512_vnni");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c6i", "c7i");

        assertThat(feature.families()).doesNotContain("c5");
    }

    @Test
    void resolvesSveToGraviton3Families() {
        IsaFeature feature = mapping.resolve("sve");
        assertThat(feature).isNotNull();
        assertThat(feature.architecture()).isEqualTo("aarch64");
        assertThat(feature.families()).contains("c7g", "m7g", "r7g");

        assertThat(feature.families()).doesNotContain("c6g");
    }

    @Test
    void resolvesSve2ToGraviton4Families() {
        IsaFeature feature = mapping.resolve("sve2");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c8g", "m8g");

        assertThat(feature.families()).doesNotContain("c7g");
    }

    @Test
    void resolvesAmxToSapphireRapidsFamilies() {
        IsaFeature feature = mapping.resolve("amx");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c7i", "m7i");
        assertThat(feature.families()).doesNotContain("c6i");
    }

    @Test
    void caseInsensitiveLookup() {
        assertThat(mapping.resolve("AVX512")).isNotNull();
        assertThat(mapping.resolve("Sve")).isNotNull();
        assertThat(mapping.resolve("NEON")).isNotNull();
    }

    @Test
    void unknownFeatureReturnsNull() {
        assertThat(mapping.resolve("nonexistent_feature")).isNull();
    }

    @Test
    void allFeatureNamesNotEmpty() {
        List<String> names = mapping.allFeatureNames();
        assertThat(names).isNotEmpty();
        assertThat(names).contains("avx512", "sve", "neon", "amx");
    }

    @Test
    void allFeaturesMatchAllNames() {
        assertThat(mapping.allFeatures()).hasSameSizeAs(mapping.allFeatureNames());
    }

    @Test
    void neonIncludesAllGravitonGenerations() {
        IsaFeature feature = mapping.resolve("neon");
        assertThat(feature).isNotNull();
        assertThat(feature.families()).contains("c6g", "c7g", "c8g");
    }
}
