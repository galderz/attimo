package org.mendrugo.attimo.ssh;

import java.util.List;

/**
 * Packages required for OpenJDK development on Amazon Linux 2023.
 *
 * <p>Package notes:
 * <ul>
 *   <li>Boot JDK: Amazon Corretto 25 (installed from Corretto yum repo,
 *       not in default AL2023 repos)</li>
 *   <li>capstone: built from source (not in AL2023 repos)</li>
 *   <li>{@code cups-devel} (not {@code libcups-devel} as on Fedora)</li>
 * </ul>
 */
public final class OsPackages
{
    private OsPackages() {}

    /**
     * Commands to add the Amazon Corretto yum repo and install JDK 25.
     * The default AL2023 repos only ship LTS versions (21), so the
     * Corretto repo is needed for JDK 25.
     */
    public static final List<String> CORRETTO_25_INSTALL_COMMANDS = List.of(
        "sudo rpm --import https://yum.corretto.aws/corretto.key"
        , "sudo curl -Lo /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo"
        , "sudo dnf install -y java-25-amazon-corretto-devel"
    );

    /**
     * Packages for OpenJDK build + test on Amazon Linux 2023.
     * Does not include the boot JDK — see {@link #CORRETTO_25_INSTALL_COMMANDS}.
     */
    public static final List<String> JDK_DEV_PACKAGES = List.of(
        "gcc"
        , "gcc-c++"
        , "make"
        , "autoconf"
        , "cups-devel"
        , "libX11-devel"
        , "libXt-devel"
        , "libXrender-devel"
        , "libXrandr-devel"
        , "libXi-devel"
        , "libXtst-devel"
        , "alsa-lib-devel"
        , "fontconfig-devel"
        , "freetype-devel"
    );

    /**
     * Commands to install capstone from source (not in AL2023 repos).
     * Run after the dnf packages are installed.
     */
    public static final List<String> CAPSTONE_INSTALL_COMMANDS = List.of(
        "sudo dnf install -y cmake git"
        , "git clone --depth 1 --branch 5.0.6 https://github.com/capstone-engine/capstone.git /tmp/capstone"
        , "cd /tmp/capstone && cmake -B build -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=Release"
        , "cd /tmp/capstone && cmake --build build -j$(nproc)"
        , "cd /tmp/capstone && sudo cmake --install build"
        , "rm -rf /tmp/capstone"
    );
}
