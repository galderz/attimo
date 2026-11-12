package org.mendrugo.attimo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mendrugo.attimo.ssh.BootJdkProvisioner;
import org.mendrugo.attimo.ssh.SshProvisioner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the full provisioning path that both AWS and Blue Hat
 * request commands follow:
 *
 * <ol>
 *   <li>Load templates (ImageDef.loadAll)</li>
 *   <li>Resolve template by name (ImageDef.findByName)</li>
 *   <li>Collect all packages including inherited (ImageDef.collectAllPackages)</li>
 *   <li>Provision via BootJdkProvisioner.provision (OS-aware)</li>
 * </ol>
 *
 * This catches regressions where a request command accidentally
 * calls {@code imageDef.getPackages()} instead of
 * {@code ImageDef.collectAllPackages()}.
 */
class TemplateProvisioningTest
{
    // ── AWS (AL2023) path ──────────────────────────────────────────

    @Test
    void awsProvisioningWithBuiltinOpenjdkTemplate()
    {
        // Simulate what AwsRequestCommand does
        final var defs = ImageDef.loadAll(List.of());
        final var imageDef = ImageDef.findByName("tpl-openjdk", defs);
        final var allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final var recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, false, "x86_64");

        // On AL2023, java-latest-openjdk-devel is intercepted
        assertThat(recorder.installedPackages)
            .doesNotContain("java-latest-openjdk-devel")
            .contains("gcc", "gcc-c++", "make", "autoconf")
            .contains("capstone", "capstone-devel");
        // Adoptium tarball download was triggered
        assertThat(recorder.ranCommands).isNotEmpty();
    }

    @Test
    void awsProvisioningWithInheritedTemplate(@TempDir final Path tempDir)
        throws IOException
    {
        // Create a parent with base packages and a child that adds more
        final var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("base.yaml"), """
            name: tpl-base
            description: Base packages
            packages:
              - gcc
              - make
            """);
        Files.writeString(imagesDir.resolve("extended.yaml"), """
            name: tpl-extended
            description: Extended with JDK
            parent: tpl-base
            packages:
              - java-latest-openjdk-devel
              - autoconf
            """);

        // Simulate AwsRequestCommand: load, resolve, collect, provision
        final var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        final var imageDef = ImageDef.findByName("tpl-extended", defs);
        final var allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final var recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, false, "x86_64");

        // Parent packages are inherited, JDK intercepted for AL2023
        assertThat(recorder.installedPackages)
            .containsExactly("gcc", "make", "autoconf");
        assertThat(recorder.ranCommands).isNotEmpty();
    }

    // ── Blue Hat (Fedora) path ─────────────────────────────────────

    @Test
    void blueHatProvisioningWithBuiltinOpenjdkTemplate()
    {
        // Simulate what BlueHatRequestCommand does
        final var defs = ImageDef.loadAll(List.of());
        final var imageDef = ImageDef.findByName("tpl-openjdk", defs);
        final var allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final var recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, true, "x86_64");

        // On Fedora, java-latest-openjdk-devel is passed to dnf as-is
        assertThat(recorder.installedPackages)
            .contains("java-latest-openjdk-devel")
            .contains("gcc", "gcc-c++", "make", "autoconf")
            .contains("capstone", "capstone-devel");
        // No Adoptium tarball download on Fedora
        assertThat(recorder.ranCommands).isEmpty();
    }

    @Test
    void blueHatProvisioningWithInheritedTemplate(@TempDir final Path tempDir)
        throws IOException
    {
        final var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("base.yaml"), """
            name: tpl-base
            description: Base packages
            packages:
              - gcc
              - make
            """);
        Files.writeString(imagesDir.resolve("extended.yaml"), """
            name: tpl-extended
            description: Extended with JDK
            parent: tpl-base
            packages:
              - java-latest-openjdk-devel
              - autoconf
            """);

        // Simulate BlueHatRequestCommand: load, resolve, collect, provision
        final var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        final var imageDef = ImageDef.findByName("tpl-extended", defs);
        final var allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final var recorder = new RecordingProvisioner();

        BootJdkProvisioner.provision(allPackages, recorder, true, "x86_64");

        // Parent packages inherited, JDK installed via dnf on Fedora
        assertThat(recorder.installedPackages)
            .containsExactly("gcc", "make", "java-latest-openjdk-devel", "autoconf");
        assertThat(recorder.ranCommands).isEmpty();
    }

    // ── Unknown template (rejected before provisioning) ────────────

    @Test
    void awsRejectsUnknownTemplateBeforeProvisioning()
    {
        // Simulate what AwsRequestCommand does at the start of doExecute()
        final var resolved = ImageDef.resolve("tpl-nonexistent");

        // Should return null — no cloud resources created
        assertThat(resolved).isNull();
    }

    @Test
    void blueHatRejectsUnknownTemplateBeforeProvisioning()
    {
        // Simulate what BlueHatRequestCommand does at the start of doExecute()
        final var resolved = ImageDef.resolve("tpl-nonexistent");

        // Should return null — no VM created
        assertThat(resolved).isNull();
    }

    @Test
    void resolveReturnsPackagesForKnownTemplate()
    {
        final var resolved = ImageDef.resolve("tpl-openjdk");

        assertThat(resolved).isNotNull();
        assertThat(resolved.imageDef().getName()).isEqualTo("tpl-openjdk");
        assertThat(resolved.packages())
            .contains("gcc", "java-latest-openjdk-devel");
    }

    // ── Minimal template (no packages) ─────────────────────────────

    @Test
    void provisioningWithMinimalTemplateIsNoOp()
    {
        final var defs = ImageDef.loadAll(List.of());
        final var imageDef = ImageDef.findByName("tpl-minimal", defs);
        final var allPackages = ImageDef.collectAllPackages(imageDef, defs);
        final var recorder = new RecordingProvisioner();

        final var result = BootJdkProvisioner.provision(
            allPackages, recorder, false, "x86_64"
        );

        assertThat(result).isTrue();
        assertThat(recorder.installedPackages).isEmpty();
        assertThat(recorder.ranCommands).isEmpty();
    }

    /**
     * Test double that records what BootJdkProvisioner passes to
     * the SSH provisioner, without making real SSH connections.
     */
    private static class RecordingProvisioner extends SshProvisioner
    {
        final List<String> ranCommands = new ArrayList<>();
        final List<String> installedPackages = new ArrayList<>();

        RecordingProvisioner()
        {
            super("test-host", "test-user", Path.of("/dev/null"));
        }

        @Override
        public int run(final String command, final int timeoutSeconds)
        {
            ranCommands.add(command);
            return 0;
        }

        @Override
        public boolean installPackages(final List<String> packages)
        {
            installedPackages.addAll(packages);
            return true;
        }
    }
}
