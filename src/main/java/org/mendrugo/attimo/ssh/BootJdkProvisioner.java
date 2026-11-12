package org.mendrugo.attimo.ssh;

import java.util.ArrayList;
import java.util.List;

public final class BootJdkProvisioner {

    public static final String JDK_PACKAGE = "java-latest-openjdk-devel";

    public static final String JDK_INSTALL_DIR = "$HOME/opt/java";

    private BootJdkProvisioner() {}

    public static void provision(List<String> packages, SshProvisioner provisioner, boolean isFedora,
            final String arch) {
        if (packages.isEmpty()) {
            return;
        }

        final ArrayList<String> dnfPackages = new ArrayList<>(packages);

        if (!isFedora && dnfPackages.remove(JDK_PACKAGE)) {

            if (!installAdoptiumTarball(provisioner, arch)) {
                System.err.println("  Warning: Adoptium Temurin install failed.");
            }
        }

        if (!provisioner.installPackages(dnfPackages)) {
            System.err.println("  Warning: package installation failed.");
        }
    }

    static boolean installAdoptiumTarball(SshProvisioner provisioner, String arch) {

        final String apiArch = "aarch64".equals(arch) ? "aarch64" : "x64";

        System.out.println("  Installing Adoptium Temurin JDK...");

        System.out.println("  Querying Adoptium API for latest release...");
        final int versionRc = provisioner.run(
                "curl -sL https://api.adoptium.net/v3/info/available_releases" + " | grep -o '\"most_recent_feature_release\": *[0-9]*'" + " | grep -o '[0-9]*'" + " > /tmp/jdk-version.txt",
                30);
        if (versionRc != 0) {
            System.err.println("  Error: failed to query Adoptium API (exit " + versionRc + ").");
            System.err.println("  Check network connectivity on the instance.");
            return false;
        }

        System.out.println("  Downloading Adoptium Temurin JDK (" + apiArch + ")...");
        final String downloadCmd = String.format(
                "mkdir -p \"${HOME}/opt\""
                        + " && VERSION=$(cat /tmp/jdk-version.txt)"
                        + " && test -n \"${VERSION}\""
                        + " && echo \"  Latest release: JDK ${VERSION}\""
                        + " && curl -fSL"
                        + " \"https://api.adoptium.net/v3/binary/latest/${VERSION}/ga/linux/%s/jdk/hotspot/normal/eclipse\""
                        + " -o \"${HOME}/opt/jdk.tar.gz\"",
                apiArch);
        final int downloadRc = provisioner.run(downloadCmd, 300);
        if (downloadRc != 0) {
            System.err.println("  Error: failed to download Adoptium Temurin (exit " + downloadRc + ").");
            System.err.println("  Check /tmp/jdk-version.txt on the instance for the detected version.");
            return false;
        }

        System.out.println("  Extracting...");
        final String extractCmd = String.format("VERSION=$(cat /tmp/jdk-version.txt)"
                + " && mkdir -p \"${HOME}/opt/java-${VERSION}\""
                + " && tar -xzf \"${HOME}/opt/jdk.tar.gz\" -C \"${HOME}/opt/java-${VERSION}\" --strip-components=1"
                + " && ln -sfn \"${HOME}/opt/java-${VERSION}\" %s"
                + " && rm -f \"${HOME}/opt/jdk.tar.gz\" /tmp/jdk-version.txt"
                + " && echo \"  Boot JDK installed: ${HOME}/opt/java-${VERSION}\""
                + " && echo \"  Symlinked: %s -> ${HOME}/opt/java-${VERSION}\"",
                JDK_INSTALL_DIR, JDK_INSTALL_DIR);
        final int extractRc = provisioner.run(extractCmd, 120);
        if (extractRc != 0) {
            System.err.println("  Error: failed to extract Adoptium Temurin (exit " + extractRc + ").");
            return false;
        }

        return true;
    }
}
