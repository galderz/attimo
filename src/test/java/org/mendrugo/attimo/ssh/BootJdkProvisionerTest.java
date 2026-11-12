package org.mendrugo.attimo.ssh;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BootJdkProvisionerTest {
    @Test
    void jdkPackageConstantMatchesOpenjdkYaml() {

        assertThat(BootJdkProvisioner.JDK_PACKAGE).isEqualTo("java-latest-openjdk-devel");
    }

    @Test
    void fedoraKeepsJdkPackageInDnfList() {

        final var packages = List.of("java-latest-openjdk-devel", "gcc", "make");

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, true, "x86_64");

        assertThat(recorder.installedPackages).contains("java-latest-openjdk-devel", "gcc", "make");

        assertThat(recorder.ranCommands).isEmpty();
    }

    @Test
    void al2023RemovesJdkPackageFromDnfList() {
        final var packages = List.of("java-latest-openjdk-devel", "gcc", "make");

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, false, "x86_64");

        assertThat(recorder.installedPackages)
                .contains("gcc", "make")
                .doesNotContain("java-latest-openjdk-devel");

        assertThat(recorder.ranCommands).hasSize(3);
        assertThat(recorder.ranCommands.get(0)).contains("adoptium");
        assertThat(recorder.ranCommands.get(1))
                .contains("adoptium")
                .contains("x64");
        assertThat(recorder.ranCommands.get(2)).contains("$HOME/opt/java");
    }

    @Test
    void al2023UsesAarch64ForArm() {
        final var packages = List.of("java-latest-openjdk-devel");

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, false, "aarch64");

        assertThat(recorder.ranCommands).hasSize(3);

        assertThat(recorder.ranCommands.get(1))
                .contains("aarch64")
                .doesNotContain("/x64/");
    }

    @Test
    void noJdkPackageMeansNothingSpecial() {

        final var packages = List.of("gcc", "make");

        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(packages, recorder, false, "x86_64");

        assertThat(recorder.ranCommands).isEmpty();
        assertThat(recorder.installedPackages).containsExactly("gcc", "make");
    }

    @Test
    void emptyPackagesIsNoOp() {
        final var recorder = new RecordingProvisioner();
        BootJdkProvisioner.provision(List.of(), recorder, false, "x86_64");
        assertThat(recorder.ranCommands).isEmpty();
        assertThat(recorder.installedPackages).isEmpty();
    }

    private static class RecordingProvisioner extends SshProvisioner {
        final List<String> ranCommands = new ArrayList<>();
        final List<String> installedPackages = new ArrayList<>();

        RecordingProvisioner() {
            super("test-host", "test-user", Path.of("/dev/null"));
        }

        @Override
        public int run(final String command, final int timeoutSeconds) {
            ranCommands.add(command);
            return 0;
        }

        @Override
        public boolean installPackages(final List<String> packages) {
            installedPackages.addAll(packages);
            return true;
        }
    }
}
