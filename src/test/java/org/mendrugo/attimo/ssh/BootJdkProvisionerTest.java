package org.mendrugo.attimo.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BootJdkProvisionerTest
{
    @Test
    void jdkPackageConstantMatchesOpenjdkYaml()
    {
        // The constant must match what's in images/openjdk.yaml
        assertThat(BootJdkProvisioner.JDK_PACKAGE)
            .isEqualTo("java-latest-openjdk-devel");
    }

    @Test
    void fedoraKeepsJdkPackageInDnfList()
    {
        // On Fedora, java-latest-openjdk-devel stays in the dnf list
        final var packages = List.of(
            "java-latest-openjdk-devel"
            , "gcc"
            , "make"
        );

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, true, "x86_64");

        // All packages passed to dnf including the JDK
        assertThat(recorder.installedPackages)
            .contains("java-latest-openjdk-devel", "gcc", "make");
        // No SSH commands run (no tarball download)
        assertThat(recorder.ranCommands).isEmpty();
    }

    @Test
    void al2023RemovesJdkPackageFromDnfList()
    {
        final var packages = List.of(
            "java-latest-openjdk-devel"
            , "gcc"
            , "make"
        );

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, false, "x86_64");

        // JDK removed from dnf list
        assertThat(recorder.installedPackages)
            .contains("gcc", "make")
            .doesNotContain("java-latest-openjdk-devel");
        // Version check + download + extract
        assertThat(recorder.ranCommands).hasSize(3);
        assertThat(recorder.ranCommands.get(0)).contains("adoptium");
        assertThat(recorder.ranCommands.get(1)).contains("adoptium").contains("x64");
        assertThat(recorder.ranCommands.get(2)).contains("/opt/jdk");
    }

    @Test
    void al2023UsesAarch64ForArm()
    {
        final var packages = List.of("java-latest-openjdk-devel");

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, false, "aarch64");

        assertThat(recorder.ranCommands).hasSize(3);
        // Download step uses aarch64
        assertThat(recorder.ranCommands.get(1))
            .contains("aarch64")
            .doesNotContain("/x64/");
    }

    @Test
    void noJdkPackageMeansNothingSpecial()
    {
        // Template without java-latest-openjdk-devel
        final var packages = List.of("gcc", "make");

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, false, "x86_64");

        // No tarball download, all packages go to dnf
        assertThat(recorder.ranCommands).isEmpty();
        assertThat(recorder.installedPackages).containsExactly("gcc", "make");
    }

    @Test
    void emptyPackagesIsNoOp()
    {
        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(
            List.of(), recorder, false, "x86_64"
        );
        assertThat(recorder.ranCommands).isEmpty();
        assertThat(recorder.installedPackages).isEmpty();
    }

    /**
     * A test double for SshProvisioner that records what was called
     * instead of making real SSH connections.
     */
    private static class RecordingProvisioner extends SshProvisioner
    {
        final java.util.List<String> ranCommands = new java.util.ArrayList<>();
        final java.util.List<String> installedPackages = new java.util.ArrayList<>();

        RecordingProvisioner()
        {
            super("test-host", "test-user", java.nio.file.Path.of("/dev/null"));
        }

        @Override
        public int run(final String command, final int timeoutSeconds)
        {
            ranCommands.add(command);
            return 0;
        }

        @Override
        public boolean installPackages(final java.util.List<String> packages)
        {
            installedPackages.addAll(packages);
            return true;
        }
    }
}
