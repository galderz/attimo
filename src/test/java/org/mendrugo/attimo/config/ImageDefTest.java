package org.mendrugo.attimo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDefTest
{
    @Test
    void minimalIsRoot()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var minimal = defs.get("tpl-minimal");
        assertThat(minimal).isNotNull();
        assertThat(minimal.isRoot()).isTrue();
        assertThat(minimal.getName()).isEqualTo("tpl-minimal");
        assertThat(minimal.getDescription()).isEqualTo("Base OS only");
        assertThat(minimal.getPackages()).isEmpty();
    }

    @Test
    void openjdkHasParent()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk).isNotNull();
        assertThat(openjdk.isRoot()).isFalse();
        assertThat(openjdk.getParent()).isEqualTo("tpl-minimal");
        assertThat(openjdk.getDescription()).isEqualTo("OpenJDK build + test environment");
    }

    @Test
    void openjdkPackagesIncludeBuildEssentials()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages())
            .contains("gcc", "gcc-c++", "make", "autoconf");
    }

    @Test
    void openjdkPackagesIncludeJdk()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages())
            .contains("java-latest-openjdk-devel");
    }

    @Test
    void openjdkPackagesIncludeXLibs()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages())
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
    void openjdkPackagesIncludeCapstone()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages())
            .contains("capstone", "capstone-devel");
    }

    @Test
    void ancestorsOfOpenjdkIsMinimal()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var openjdk = defs.get("tpl-openjdk");
        final var ancestors = ImageDef.ancestors(openjdk, defs);
        assertThat(ancestors).hasSize(1);
        assertThat(ancestors.getFirst().getName()).isEqualTo("tpl-minimal");
    }

    @Test
    void ancestorsOfMinimalIsEmpty()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var minimal = defs.get("tpl-minimal");
        final var ancestors = ImageDef.ancestors(minimal, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void loadAllReturnsAllBuiltins()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void findByNameReturnsCorrectDef()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var found = ImageDef.findByName("tpl-openjdk", defs);
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("tpl-openjdk");
    }

    @Test
    void findByNameReturnsNullForUnknown()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var found = ImageDef.findByName("tpl-nonexistent", defs);
        assertThat(found).isNull();
    }

    @Test
    void parseYamlWithNoParent() throws IOException
    {
        final var yaml = """
            name: tpl-test
            description: Test template
            """;
        final var def = ImageDef.parseYaml(yaml);
        assertThat(def.getName()).isEqualTo("tpl-test");
        assertThat(def.isRoot()).isTrue();
        assertThat(def.getPackages()).isEmpty();
    }

    @Test
    void parseYamlWithParentAndPackages() throws IOException
    {
        final var yaml = """
            name: tpl-child
            description: Child template
            parent: tpl-test
            packages:
              - gcc
              - make
            """;
        final var def = ImageDef.parseYaml(yaml);
        assertThat(def.getName()).isEqualTo("tpl-child");
        assertThat(def.isRoot()).isFalse();
        assertThat(def.getParent()).isEqualTo("tpl-test");
        assertThat(def.getPackages()).containsExactly("gcc", "make");
    }

    @Test
    void parseYamlIgnoresUnknownFields() throws IOException
    {
        final var yaml = """
            name: tpl-test
            description: Test
            unknown_field: should be ignored
            """;
        final var def = ImageDef.parseYaml(yaml);
        assertThat(def.getName()).isEqualTo("tpl-test");
    }

    @Test
    void isRootWithBlankParent() throws IOException
    {
        final var yaml = """
            name: tpl-test
            parent: ""
            """;
        final var def = ImageDef.parseYaml(yaml);
        assertThat(def.isRoot()).isTrue();
    }

    @Test
    void ancestorChainStopsOnMissingParent()
    {
        final var defs = Map.of(
            "child", createDef("child", "missing-parent")
        );
        final var child = defs.get("child");
        final var ancestors = ImageDef.ancestors(child, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void searchPathLoadsExternalTemplates(@TempDir final Path tempDir) throws IOException
    {
        // Create a search path with images/ subdirectory
        final var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("custom.yaml"), """
            name: tpl-custom
            description: Custom template
            parent: tpl-minimal
            packages:
              - vim
            """);

        final var defs = ImageDef.loadAll(List.of(tempDir.toString()), null);

        // Built-ins still loaded
        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");
        // Custom template loaded from search path
        assertThat(defs).containsKey("tpl-custom");
        final var custom = defs.get("tpl-custom");
        assertThat(custom.getParent()).isEqualTo("tpl-minimal");
        assertThat(custom.getPackages()).containsExactly("vim");
    }

    @Test
    void searchPathOverridesBuiltin(@TempDir final Path tempDir) throws IOException
    {
        final var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("openjdk.yaml"), """
            name: tpl-openjdk
            description: Overridden OpenJDK
            parent: tpl-minimal
            packages:
              - gcc
            """);

        final var defs = ImageDef.loadAll(List.of(tempDir.toString()), null);
        final var openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getDescription()).isEqualTo("Overridden OpenJDK");
        assertThat(openjdk.getPackages()).containsExactly("gcc");
    }

    @Test
    void emptySearchPathsLoadsOnlyBuiltins()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        assertThat(defs).containsOnlyKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void nonExistentSearchPathIsIgnored()
    {
        final var defs = ImageDef.loadAll(List.of("/nonexistent/path"));
        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void expandTildeExpandsHome()
    {
        final var expanded = ImageDef.expandTilde("~/templates");
        assertThat(expanded).startsWith(System.getProperty("user.home"));
        assertThat(expanded).endsWith("/templates");
    }

    @Test
    void expandTildeIgnoresAbsolutePath()
    {
        final var path = "/opt/templates";
        assertThat(ImageDef.expandTilde(path)).isEqualTo(path);
    }

    @Test
    void ancestorChainStopsOnCycle()
    {
        final var a = createDef("a", "b");
        final var b = createDef("b", "a");
        final var defs = Map.of("a", a, "b", b);
        final var ancestors = ImageDef.ancestors(a, defs);
        // Should stop instead of looping forever
        assertThat(ancestors).hasSize(1);
        assertThat(ancestors.getFirst().getName()).isEqualTo("b");
    }

    @Test
    void ancestorChainStopsOnSelfReference()
    {
        final var self = createDef("self", "self");
        final var defs = Map.of("self", self);
        final var ancestors = ImageDef.ancestors(self, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void collectAllPackagesFromLeafOnly()
    {
        final var root = createDef("root", null);
        final var child = createDefWithPackages("child", "root"
            , List.of("gcc", "make"));
        final var defs = Map.of("root", root, "child", child);

        final var packages = ImageDef.collectAllPackages(child, defs);
        assertThat(packages).containsExactly("gcc", "make");
    }

    @Test
    void collectAllPackagesInheritsFromParent()
    {
        final var root = createDefWithPackages("root", null
            , List.of("base-pkg"));
        final var child = createDefWithPackages("child", "root"
            , List.of("child-pkg"));
        final var defs = Map.of("root", root, "child", child);

        final var packages = ImageDef.collectAllPackages(child, defs);
        // Root packages first, then child
        assertThat(packages).containsExactly("base-pkg", "child-pkg");
    }

    @Test
    void collectAllPackagesDeduplicates()
    {
        final var root = createDefWithPackages("root", null
            , List.of("gcc", "make"));
        final var child = createDefWithPackages("child", "root"
            , List.of("make", "autoconf"));
        final var defs = Map.of("root", root, "child", child);

        final var packages = ImageDef.collectAllPackages(child, defs);
        // 'make' appears in both but should only be listed once
        assertThat(packages).containsExactly("gcc", "make", "autoconf");
    }

    @Test
    void collectAllPackagesThreeLevelChain()
    {
        final var gp = createDefWithPackages("gp", null
            , List.of("gp-pkg"));
        final var p = createDefWithPackages("parent", "gp"
            , List.of("parent-pkg"));
        final var child = createDefWithPackages("child", "parent"
            , List.of("child-pkg"));
        final var defs = Map.of("gp", gp, "parent", p, "child", child);

        final var packages = ImageDef.collectAllPackages(child, defs);
        assertThat(packages).containsExactly(
            "gp-pkg", "parent-pkg", "child-pkg");
    }

    @Test
    void collectAllPackagesBuiltinOpenjdkInheritsMinimal()
    {
        final var defs = ImageDef.loadAll(List.of(), null);
        final var openjdk = defs.get("tpl-openjdk");
        final var packages = ImageDef.collectAllPackages(openjdk, defs);
        assertThat(packages).contains("gcc", "java-latest-openjdk-devel");
    }

    @Test
    void collectAllPackagesRootTemplate()
    {
        final var root = createDefWithPackages("root", null
            , List.of("pkg1"));
        final var defs = Map.of("root", root);
        final var packages = ImageDef.collectAllPackages(root, defs);
        assertThat(packages).containsExactly("pkg1");
    }

    @Test
    void setPackagesNullDefaultsToEmpty() throws IOException
    {
        final var yaml = """
            name: tpl-test
            packages:
            """;
        final var def = ImageDef.parseYaml(yaml);
        assertThat(def.getPackages()).isNotNull().isEmpty();
    }

    private static ImageDef createDef(
        final String name
        , final String parent
    )
    {
        final var def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        return def;
    }

    private static ImageDef createDefWithPackages(
        final String name
        , final String parent
        , final List<String> packages
    )
    {
        final var def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        def.setPackages(packages);
        return def;
    }
}
