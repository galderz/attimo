package org.mendrugo.attimo.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OsPackagesTest
{
    @Test
    void detectsFedoraFromResolvedName()
    {
        assertThat(OsPackages.detectOs("fedora-44")).isEqualTo(OsPackages.Os.FEDORA);
        assertThat(OsPackages.detectOs("fedora-43")).isEqualTo(OsPackages.Os.FEDORA);
        assertThat(OsPackages.detectOs("fedora-41")).isEqualTo(OsPackages.Os.FEDORA);
    }

    @Test
    void detectsAl2023FromResolvedName()
    {
        assertThat(OsPackages.detectOs("al2023")).isEqualTo(OsPackages.Os.AL2023);
    }

    @Test
    void defaultsToFedoraForUnknown()
    {
        assertThat(OsPackages.detectOs("something-else")).isEqualTo(OsPackages.Os.FEDORA);
    }

    @Test
    void fedoraPackagesAreUnchanged()
    {
        final var packages = List.of("gcc", "java-25-openjdk-devel", "capstone");
        final var result = OsPackages.resolve(packages, OsPackages.Os.FEDORA);

        assertThat(result.installable()).isEqualTo(packages);
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void al2023MapsJdkToCorretto()
    {
        final var packages = List.of("java-25-openjdk-devel", "java-25-openjdk-javadoc");
        final var result = OsPackages.resolve(packages, OsPackages.Os.AL2023);

        assertThat(result.installable()).containsExactly(
            "java-21-amazon-corretto-devel"
            , "java-21-amazon-corretto-javadoc"
        );
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void al2023SkipsUnavailablePackages()
    {
        final var packages = List.of(
            "capstone", "capstone-devel", "capstone-tool"
            , "java-25-openjdk-src"
        );
        final var result = OsPackages.resolve(packages, OsPackages.Os.AL2023);

        assertThat(result.installable()).isEmpty();
        assertThat(result.skipped()).containsExactlyInAnyOrder(
            "capstone", "capstone-devel", "capstone-tool"
            , "java-25-openjdk-src"
        );
    }

    @Test
    void al2023MapsLibcupsToCups()
    {
        final var packages = List.of("libcups-devel");
        final var result = OsPackages.resolve(packages, OsPackages.Os.AL2023);

        assertThat(result.installable()).containsExactly("cups-devel");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void al2023KeepsCommonPackagesUnchanged()
    {
        final var packages = List.of(
            "gcc", "gcc-c++", "make", "autoconf"
            , "libX11-devel", "alsa-lib-devel", "fontconfig-devel"
        );
        final var result = OsPackages.resolve(packages, OsPackages.Os.AL2023);

        assertThat(result.installable()).isEqualTo(packages);
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void fullJdkDevPackageListResolvesForAl2023()
    {
        final var result = OsPackages.resolve(
            OsPackages.JDK_DEV_PACKAGES, OsPackages.Os.AL2023
        );

        // Should have mapped packages
        assertThat(result.installable())
            .contains("java-21-amazon-corretto-devel")
            .contains("cups-devel")
            .contains("gcc")
            .doesNotContain("java-25-openjdk-devel")
            .doesNotContain("libcups-devel")
            .doesNotContain("capstone");

        // Should have skipped unavailable ones
        assertThat(result.skipped())
            .contains("capstone", "capstone-devel", "capstone-tool")
            .contains("java-25-openjdk-src");
    }

    @Test
    void fullJdkDevPackageListResolvesForFedora()
    {
        final var result = OsPackages.resolve(
            OsPackages.JDK_DEV_PACKAGES, OsPackages.Os.FEDORA
        );

        assertThat(result.installable()).isEqualTo(OsPackages.JDK_DEV_PACKAGES);
        assertThat(result.skipped()).isEmpty();
    }
}
