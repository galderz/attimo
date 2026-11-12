package org.mendrugo.attimo.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDefTest {
    @Test
    void minimalIsRoot() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef minimal = defs.get("tpl-minimal");
        assertThat(minimal).isNotNull();
        assertThat(minimal.isRoot()).isTrue();
        assertThat(minimal.getName()).isEqualTo("tpl-minimal");
        assertThat(minimal.getDescription()).isEqualTo("Base OS only");
        assertThat(minimal.getPackages()).isEmpty();
    }

    @Test
    void openjdkHasParent() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk).isNotNull();
        assertThat(openjdk.isRoot()).isFalse();
        assertThat(openjdk.getParent()).isEqualTo("tpl-minimal");
        assertThat(openjdk.getDescription()).isEqualTo("OpenJDK build + test environment");
    }

    @Test
    void openjdkPackagesIncludeBuildEssentials() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages()).contains("gcc", "gcc-c++", "make", "autoconf");
    }

    @Test
    void openjdkPackagesIncludeJdk() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages()).contains("java-latest-openjdk-devel");
    }

    @Test
    void openjdkPackagesIncludeXLibs() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages()).contains("libX11-devel", "libXt-devel", "libXrender-devel",
                "libXrandr-devel", "libXi-devel", "libXtst-devel");
    }

    @Test
    void openjdkPackagesIncludeCapstone() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getPackages()).contains("capstone", "capstone-devel");
    }

    @Test
    void ancestorsOfOpenjdkIsMinimal() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        final List<ImageDef> ancestors = ImageDef.ancestors(openjdk, defs);
        assertThat(ancestors).hasSize(1);
        assertThat(ancestors.getFirst().getName()).isEqualTo("tpl-minimal");
    }

    @Test
    void ancestorsOfMinimalIsEmpty() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef minimal = defs.get("tpl-minimal");
        final List<ImageDef> ancestors = ImageDef.ancestors(minimal, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void loadAllReturnsAllBuiltins() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void findByNameReturnsCorrectDef() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef found = ImageDef.findByName("tpl-openjdk", defs);
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("tpl-openjdk");
    }

    @Test
    void findByNameReturnsNullForUnknown() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef found = ImageDef.findByName("tpl-nonexistent", defs);
        assertThat(found).isNull();
    }

    @Test
    void parseYamlWithNoParent() throws IOException {
        final String yaml =
                """
            name: tpl-test
            description: Test template
            """;
        final ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.getName()).isEqualTo("tpl-test");
        assertThat(def.isRoot()).isTrue();
        assertThat(def.getPackages()).isEmpty();
    }

    @Test
    void parseYamlWithParentAndPackages() throws IOException {
        final String yaml =
                """
            name: tpl-child
            description: Child template
            parent: tpl-test
            packages:
              - gcc
              - make
            """;
        final ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.getName()).isEqualTo("tpl-child");
        assertThat(def.isRoot()).isFalse();
        assertThat(def.getParent()).isEqualTo("tpl-test");
        assertThat(def.getPackages()).containsExactly("gcc", "make");
    }

    @Test
    void parseYamlIgnoresUnknownFields() throws IOException {
        final String yaml =
                """
            name: tpl-test
            description: Test
            unknown_field: should be ignored
            """;
        final ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.getName()).isEqualTo("tpl-test");
    }

    @Test
    void isRootWithBlankParent() throws IOException {
        final String yaml =
                """
            name: tpl-test
            parent: ""
            """;
        final ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.isRoot()).isTrue();
    }

    @Test
    void ancestorChainStopsOnMissingParent() {
        final Map<String, ImageDef> defs = Map.of("child", createDef("child", "missing-parent"));
        final ImageDef child = defs.get("child");
        final List<ImageDef> ancestors = ImageDef.ancestors(child, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void searchPathLoadsExternalTemplates(@TempDir final Path tempDir) throws IOException {
        final Path imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("custom.yaml"),
                """
            name: tpl-custom
            description: Custom template
            parent: tpl-minimal
            packages:
              - vim
            """);

        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(tempDir.toString()), null);

        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");

        assertThat(defs).containsKey("tpl-custom");
        final ImageDef custom = defs.get("tpl-custom");
        assertThat(custom.getParent()).isEqualTo("tpl-minimal");
        assertThat(custom.getPackages()).containsExactly("vim");
    }

    @Test
    void searchPathOverridesBuiltin(@TempDir final Path tempDir) throws IOException {
        final Path imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("openjdk.yaml"),
                """
            name: tpl-openjdk
            description: Overridden OpenJDK
            parent: tpl-minimal
            packages:
              - gcc
            """);

        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(tempDir.toString()), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.getDescription()).isEqualTo("Overridden OpenJDK");
        assertThat(openjdk.getPackages()).containsExactly("gcc");
    }

    @Test
    void emptySearchPathsLoadsOnlyBuiltins() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        assertThat(defs).containsOnlyKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void nonExistentSearchPathIsIgnored() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of("/nonexistent/path"));
        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void expandTildeExpandsHome() {
        final String expanded = ImageDef.expandTilde("~/templates");
        assertThat(expanded).startsWith(System.getProperty("user.home"));
        assertThat(expanded).endsWith("/templates");
    }

    @Test
    void expandTildeIgnoresAbsolutePath() {
        final String path = "/opt/templates";
        assertThat(ImageDef.expandTilde(path)).isEqualTo(path);
    }

    @Test
    void ancestorChainStopsOnCycle() {
        final ImageDef a = createDef("a", "b");
        final ImageDef b = createDef("b", "a");
        final Map<String, ImageDef> defs = Map.of("a", a, "b", b);
        final List<ImageDef> ancestors = ImageDef.ancestors(a, defs);

        assertThat(ancestors).hasSize(1);
        assertThat(ancestors.getFirst().getName()).isEqualTo("b");
    }

    @Test
    void ancestorChainStopsOnSelfReference() {
        final ImageDef self = createDef("self", "self");
        final Map<String, ImageDef> defs = Map.of("self", self);
        final List<ImageDef> ancestors = ImageDef.ancestors(self, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void collectAllPackagesFromLeafOnly() {
        final ImageDef root = createDef("root", null);
        final ImageDef child = createDefWithPackages("child", "root", List.of("gcc", "make"));
        final Map<String, ImageDef> defs = Map.of("root", root, "child", child);

        final List<String> packages = ImageDef.collectAllPackages(child, defs);
        assertThat(packages).containsExactly("gcc", "make");
    }

    @Test
    void collectAllPackagesInheritsFromParent() {
        final ImageDef root = createDefWithPackages("root", null, List.of("base-pkg"));
        final ImageDef child = createDefWithPackages("child", "root", List.of("child-pkg"));
        final Map<String, ImageDef> defs = Map.of("root", root, "child", child);

        final List<String> packages = ImageDef.collectAllPackages(child, defs);

        assertThat(packages).containsExactly("base-pkg", "child-pkg");
    }

    @Test
    void collectAllPackagesDeduplicates() {
        final ImageDef root = createDefWithPackages("root", null, List.of("gcc", "make"));
        final ImageDef child = createDefWithPackages("child", "root", List.of("make", "autoconf"));
        final Map<String, ImageDef> defs = Map.of("root", root, "child", child);

        final List<String> packages = ImageDef.collectAllPackages(child, defs);

        assertThat(packages).containsExactly("gcc", "make", "autoconf");
    }

    @Test
    void collectAllPackagesThreeLevelChain() {
        final ImageDef gp = createDefWithPackages("gp", null, List.of("gp-pkg"));
        final ImageDef p = createDefWithPackages("parent", "gp", List.of("parent-pkg"));
        final ImageDef child = createDefWithPackages("child", "parent", List.of("child-pkg"));
        final Map<String, ImageDef> defs = Map.of("gp", gp, "parent", p, "child", child);

        final List<String> packages = ImageDef.collectAllPackages(child, defs);
        assertThat(packages).containsExactly("gp-pkg", "parent-pkg", "child-pkg");
    }

    @Test
    void collectAllPackagesBuiltinOpenjdkInheritsMinimal() {
        final Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        final ImageDef openjdk = defs.get("tpl-openjdk");
        final List<String> packages = ImageDef.collectAllPackages(openjdk, defs);
        assertThat(packages).contains("gcc", "java-latest-openjdk-devel");
    }

    @Test
    void collectAllPackagesRootTemplate() {
        final ImageDef root = createDefWithPackages("root", null, List.of("pkg1"));
        final Map<String, ImageDef> defs = Map.of("root", root);
        final List<String> packages = ImageDef.collectAllPackages(root, defs);
        assertThat(packages).containsExactly("pkg1");
    }

    @Test
    void setPackagesNullDefaultsToEmpty() throws IOException {
        final String yaml =
                """
            name: tpl-test
            packages:
            """;
        final ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.getPackages())
                .isNotNull()
                .isEmpty();
    }

    private static ImageDef createDef(String name, String parent) {
        final ImageDef def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        return def;
    }

    private static ImageDef createDefWithPackages(String name, String parent, List<String> packages) {
        final ImageDef def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        def.setPackages(packages);
        return def;
    }
}
