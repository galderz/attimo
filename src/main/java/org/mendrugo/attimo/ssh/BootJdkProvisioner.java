package org.mendrugo.attimo.ssh;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles boot JDK installation with OS-specific strategies.
 *
 * <p>On Fedora, {@code java-latest-openjdk-devel} is available in the
 * default repos and is passed straight to {@code dnf install}.
 *
 * <p>On Amazon Linux 2023, that package doesn't exist. Instead, we
 * download the latest Adoptium Temurin tarball and extract it to
 * {@code /opt/jdk-<version>}. The path is displayed to the user.
 */
public final class BootJdkProvisioner
{
    /** The Fedora package name that triggers special handling on AL2023. */
    public static final String JDK_PACKAGE = "java-latest-openjdk-devel";

    /** Install path for tarball-based JDK on AL2023. */
    public static final String JDK_INSTALL_DIR = "/opt/jdk";

    private BootJdkProvisioner() {}

    /**
     * Partition packages into those that need dnf install and handle
     * the boot JDK separately if on AL2023.
     *
     * @param packages  full package list from the template
     * @param provisioner SSH provisioner for the target host
     * @param isFedora  true for Fedora (Blue Hat), false for AL2023 (AWS)
     * @param arch      target architecture: "x86_64" or "aarch64"
     * @return true if all installations succeeded
     */
    public static boolean provision(
        final List<String> packages
        , final SshProvisioner provisioner
        , final boolean isFedora
        , final String arch
    )
    {
        if (packages.isEmpty())
        {
            return true;
        }

        final var dnfPackages = new ArrayList<>(packages);

        if (!isFedora && dnfPackages.remove(JDK_PACKAGE))
        {
            // AL2023: download Adoptium Temurin tarball
            if (!installAdoptiumTarball(provisioner, arch))
            {
                System.err.println("  Warning: Adoptium Temurin install failed.");
            }
        }

        // Install remaining packages via dnf (on Fedora this includes
        // java-latest-openjdk-devel; on AL2023 it was removed above)
        return provisioner.installPackages(dnfPackages);
    }

    /**
     * Download and extract the latest Adoptium Temurin JDK tarball.
     * Runs each step individually over SSH so errors are visible.
     */
    static boolean installAdoptiumTarball(
        final SshProvisioner provisioner
        , final String arch
    )
    {
        // Map attimo arch names to Adoptium API arch names
        final var apiArch = "aarch64".equals(arch) ? "aarch64" : "x64";

        System.out.println("  Installing Adoptium Temurin JDK...");

        // Step 1: Query Adoptium API for the latest GA version
        System.out.println("  Querying Adoptium API for latest release...");
        final var versionScript =
            "curl -sL https://api.adoptium.net/v3/info/available_releases"
            + " | grep -o '\"most_recent_feature_release\":[0-9]*'"
            + " | grep -o '[0-9]*'";
        final var versionRc = provisioner.run(versionScript, 30);
        if (versionRc != 0)
        {
            System.err.println("  Error: failed to query Adoptium API (exit " + versionRc + ").");
            System.err.println("  Check network connectivity on the instance.");
            return false;
        }

        // Step 2: Download, extract, symlink — as a single script so $VERSION
        // is available throughout. Each command prints progress and the script
        // reports the specific failing step on error.
        final var installScript = String.format(
            "set -e; "
            + "VERSION=$(curl -sL https://api.adoptium.net/v3/info/available_releases"
            + " | grep -o '\"most_recent_feature_release\":[0-9]*'"
            + " | grep -o '[0-9]*'); "
            + "if [ -z \"$VERSION\" ]; then echo 'Error: could not determine latest JDK version' >&2; exit 1; fi; "
            + "echo \"  Downloading Adoptium Temurin JDK $VERSION (%s)...\"; "
            + "curl -fSL"
            + " \"https://api.adoptium.net/v3/binary/latest/${VERSION}/ga/linux/%s/jdk/hotspot/normal/eclipse\""
            + " -o /tmp/jdk.tar.gz; "
            + "echo \"  Extracting to /opt/jdk-${VERSION}...\"; "
            + "sudo mkdir -p /opt/jdk-${VERSION}; "
            + "sudo tar -xzf /tmp/jdk.tar.gz -C /opt/jdk-${VERSION} --strip-components=1; "
            + "sudo ln -sfn /opt/jdk-${VERSION} %s; "
            + "rm -f /tmp/jdk.tar.gz; "
            + "echo \"  Boot JDK installed: /opt/jdk-${VERSION}\"; "
            + "echo \"  Symlinked: %s -> /opt/jdk-${VERSION}\""
            , apiArch
            , apiArch
            , JDK_INSTALL_DIR
            , JDK_INSTALL_DIR
        );

        final var rc = provisioner.run("bash -c '" + installScript + "'", 300);
        if (rc != 0)
        {
            System.err.println("  Error: Adoptium Temurin install failed (exit " + rc + ").");
            System.err.println("  Check the output above for details.");
        }
        return rc == 0;
    }
}
