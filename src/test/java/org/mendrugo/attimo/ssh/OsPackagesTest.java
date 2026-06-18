package org.mendrugo.attimo.ssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OsPackagesTest
{
    @Test
    void jdkDevPackagesDoNotIncludeBootJdk()
    {
        // Boot JDK (Corretto 25) is installed separately
        assertThat(OsPackages.JDK_DEV_PACKAGES)
            .noneMatch(p -> p.contains("corretto"))
            .noneMatch(p -> p.contains("openjdk"));
    }

    @Test
    void jdkDevPackagesIncludeBuildEssentials()
    {
        assertThat(OsPackages.JDK_DEV_PACKAGES)
            .contains("gcc", "gcc-c++", "make", "autoconf");
    }

    @Test
    void jdkDevPackagesUsesAl2023Names()
    {
        // cups-devel, not libcups-devel (Fedora naming)
        assertThat(OsPackages.JDK_DEV_PACKAGES)
            .contains("cups-devel")
            .doesNotContain("libcups-devel");
    }

    @Test
    void jdkDevPackagesIncludesXLibs()
    {
        assertThat(OsPackages.JDK_DEV_PACKAGES)
            .contains(
                "libX11-devel"
                , "libXt-devel"
                , "libXrender-devel"
                , "libXrandr-devel"
                , "libXi-devel"
                , "libXtst-devel"
            );
    }

    @Test
    void correttoInstallCommandsAddRepo()
    {
        assertThat(OsPackages.CORRETTO_25_INSTALL_COMMANDS)
            .anyMatch(c -> c.contains("corretto.key"))
            .anyMatch(c -> c.contains("corretto.repo"))
            .anyMatch(c -> c.contains("java-25-amazon-corretto-devel"));
    }

    @Test
    void capstoneInstallCommandsBuildFromSource()
    {
        assertThat(OsPackages.CAPSTONE_INSTALL_COMMANDS)
            .anyMatch(c -> c.contains("capstone-engine/capstone"))
            .anyMatch(c -> c.contains("cmake --install"));
    }
}
