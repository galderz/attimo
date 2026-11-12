package org.mendrugo.attimo.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mendrugo.attimo.config.ImageDef.ResolvedTemplate;
import org.mendrugo.attimo.ssh.BootJdkProvisioner;
import org.mendrugo.attimo.ssh.SshProvisioner;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateProvisioningTest {

    @Test
    void awsProvisioningWithBuiltinOpenjdkTemplate() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef imageDef = ImageDef.findByName("tpl-openjdk", defs);
        final List<String> allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final RecordingProvisioner recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, false, "x86_64");

        assertThat(recorder.installedPackages)
                .doesNotContain("java-latest-openjdk-devel")
                .contains("gcc", "gcc-c++", "make", "autoconf")
                .contains("capstone", "capstone-devel");

        assertThat(recorder.ranCommands).isNotEmpty();
    }

    @Test
    void awsProvisioningWithInheritedTemplate(@TempDir final Path tempDir) throws IOException {
        final Path imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("base.yaml"),
                """
            name: tpl-base
            description: Base packages
            packages:
              - gcc
              - make
            """);
        Files.writeString(imagesDir.resolve("extended.yaml"),
                """
            name: tpl-extended
            description: Extended with JDK
            parent: tpl-base
            packages:
              - java-latest-openjdk-devel
              - autoconf
            """);

        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(tempDir.toString()), null);
        final ImageDef imageDef = ImageDef.findByName("tpl-extended", defs);
        final List<String> allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final RecordingProvisioner recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, false, "x86_64");

        assertThat(recorder.installedPackages).containsExactly("gcc", "make", "autoconf");
        assertThat(recorder.ranCommands).isNotEmpty();
    }

    @Test
    void blueHatProvisioningWithBuiltinOpenjdkTemplate() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef imageDef = ImageDef.findByName("tpl-openjdk", defs);
        final List<String> allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final RecordingProvisioner recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, true, "x86_64");

        assertThat(recorder.installedPackages)
                .contains("java-latest-openjdk-devel")
                .contains("gcc", "gcc-c++", "make", "autoconf")
                .contains("capstone", "capstone-devel");

        assertThat(recorder.ranCommands).isEmpty();
    }

    @Test
    void blueHatProvisioningWithInheritedTemplate(@TempDir final Path tempDir) throws IOException {
        final Path imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("base.yaml"),
                """
            name: tpl-base
            description: Base packages
            packages:
              - gcc
              - make
            """);
        Files.writeString(imagesDir.resolve("extended.yaml"),
                """
            name: tpl-extended
            description: Extended with JDK
            parent: tpl-base
            packages:
              - java-latest-openjdk-devel
              - autoconf
            """);

        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(tempDir.toString()), null);
        final ImageDef imageDef = ImageDef.findByName("tpl-extended", defs);
        final List<String> allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final RecordingProvisioner recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, true, "x86_64");

        assertThat(recorder.installedPackages).containsExactly("gcc", "make", "java-latest-openjdk-devel", "autoconf");
        assertThat(recorder.ranCommands).isEmpty();
    }

    @Test
    void awsRejectsUnknownTemplateBeforeProvisioning() {
        final ResolvedTemplate resolved = ImageDef.resolve("tpl-nonexistent");

        assertThat(resolved).isNull();
    }

    @Test
    void blueHatRejectsUnknownTemplateBeforeProvisioning() {
        final ResolvedTemplate resolved = ImageDef.resolve("tpl-nonexistent");

        assertThat(resolved).isNull();
    }

    @Test
    void resolveReturnsPackagesForKnownTemplate() {
        final ResolvedTemplate resolved = ImageDef.resolve("tpl-openjdk");

        assertThat(resolved).isNotNull();
        assertThat(resolved.imageDef().getName()).isEqualTo("tpl-openjdk");
        assertThat(resolved.packages()).contains("gcc", "java-latest-openjdk-devel");
    }

    @Test
    void provisioningWithMinimalTemplateIsNoOp() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef imageDef = ImageDef.findByName("tpl-minimal", defs);
        final List<String> allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final RecordingProvisioner recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, false, "x86_64");

        assertThat(recorder.installedPackages).isEmpty();
        assertThat(recorder.ranCommands).isEmpty();
    }

    private static class RecordingProvisioner extends SshProvisioner {
        final List<String> ranCommands = new ArrayList<>();
        final List<String> installedPackages = new ArrayList<>();

        RecordingProvisioner() {
            super("test-host", "test-user", Path.of("/dev/null"));
        }

        @Override
        public int run(String command, int timeoutSeconds) {
            ranCommands.add(command);
            return 0;
        }

        @Override
        public boolean installPackages(List<String> packages) {
            installedPackages.addAll(packages);
            return true;
        }
    }
}
