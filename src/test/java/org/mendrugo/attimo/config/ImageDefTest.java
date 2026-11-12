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
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef minimal = defs.get("tpl-minimal");
        assertThat(minimal).isNotNull();
        assertThat(minimal.isRoot()).isTrue();
        assertThat(minimal.name).isEqualTo("tpl-minimal");
        assertThat(minimal.description).isEqualTo("Base OS only");
        assertThat(minimal.packages).isEmpty();
    }

    @Test
    void openjdkHasParent() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk).isNotNull();
        assertThat(openjdk.isRoot()).isFalse();
        assertThat(openjdk.parent).isEqualTo("tpl-minimal");
        assertThat(openjdk.description).isEqualTo("OpenJDK build + test environment");
    }

    @Test
    void openjdkPackagesIncludeBuildEssentials() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.packages).contains("gcc", "gcc-c++", "make", "autoconf");
    }

    @Test
    void openjdkPackagesIncludeJdk() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.packages).contains("java-latest-openjdk-devel");
    }

    @Test
    void openjdkPackagesIncludeXLibs() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.packages).contains("libX11-devel", "libXt-devel", "libXrender-devel",
                "libXrandr-devel", "libXi-devel", "libXtst-devel");
    }

    @Test
    void openjdkPackagesIncludeCapstone() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.packages).contains("capstone", "capstone-devel");
    }

    @Test
    void ancestorsOfOpenjdkIsMinimal() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        List<ImageDef> ancestors = ImageDef.ancestors(openjdk, defs);
        assertThat(ancestors).hasSize(1);
        assertThat(ancestors.getFirst().name).isEqualTo("tpl-minimal");
    }

    @Test
    void ancestorsOfMinimalIsEmpty() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef minimal = defs.get("tpl-minimal");
        List<ImageDef> ancestors = ImageDef.ancestors(minimal, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void loadAllReturnsAllBuiltins() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void findByNameReturnsCorrectDef() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef found = ImageDef.findByName("tpl-openjdk", defs);
        assertThat(found).isNotNull();
        assertThat(found.name).isEqualTo("tpl-openjdk");
    }

    @Test
    void findByNameReturnsNullForUnknown() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef found = ImageDef.findByName("tpl-nonexistent", defs);
        assertThat(found).isNull();
    }

    @Test
    void parseYamlWithNoParent() throws IOException {
        String yaml =
                """
            name: tpl-test
            description: Test template
            """;
        ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.name).isEqualTo("tpl-test");
        assertThat(def.isRoot()).isTrue();
        assertThat(def.packages).isEmpty();
    }

    @Test
    void parseYamlWithParentAndPackages() throws IOException {
        String yaml =
                """
            name: tpl-child
            description: Child template
            parent: tpl-test
            packages:
              - gcc
              - make
            """;
        ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.name).isEqualTo("tpl-child");
        assertThat(def.isRoot()).isFalse();
        assertThat(def.parent).isEqualTo("tpl-test");
        assertThat(def.packages).containsExactly("gcc", "make");
    }

    @Test
    void parseYamlIgnoresUnknownFields() throws IOException {
        String yaml =
                """
            name: tpl-test
            description: Test
            unknown_field: should be ignored
            """;
        ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.name).isEqualTo("tpl-test");
    }

    @Test
    void isRootWithBlankParent() throws IOException {
        String yaml =
                """
            name: tpl-test
            parent: ""
            """;
        ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.isRoot()).isTrue();
    }

    @Test
    void ancestorChainStopsOnMissingParent() {
        Map<String, ImageDef> defs = Map.of("child", createDef("child", "missing-parent"));
        ImageDef child = defs.get("child");
        List<ImageDef> ancestors = ImageDef.ancestors(child, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void searchPathLoadsExternalTemplates(@TempDir final Path tempDir) throws IOException {
        Path imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("custom.yaml"),
                """
            name: tpl-custom
            description: Custom template
            parent: tpl-minimal
            packages:
              - vim
            """);

        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(tempDir.toString()), null);

        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");

        assertThat(defs).containsKey("tpl-custom");
        ImageDef custom = defs.get("tpl-custom");
        assertThat(custom.parent).isEqualTo("tpl-minimal");
        assertThat(custom.packages).containsExactly("vim");
    }

    @Test
    void searchPathOverridesBuiltin(@TempDir final Path tempDir) throws IOException {
        Path imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("openjdk.yaml"),
                """
            name: tpl-openjdk
            description: Overridden OpenJDK
            parent: tpl-minimal
            packages:
              - gcc
            """);

        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(tempDir.toString()), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        assertThat(openjdk.description).isEqualTo("Overridden OpenJDK");
        assertThat(openjdk.packages).containsExactly("gcc");
    }

    @Test
    void emptySearchPathsLoadsOnlyBuiltins() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        assertThat(defs).containsOnlyKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void nonExistentSearchPathIsIgnored() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of("/nonexistent/path"));
        assertThat(defs).containsKeys("tpl-minimal", "tpl-openjdk");
    }

    @Test
    void expandTildeExpandsHome() {
        String expanded = ImageDef.expandTilde("~/templates");
        assertThat(expanded).startsWith(System.getProperty("user.home"));
        assertThat(expanded).endsWith("/templates");
    }

    @Test
    void expandTildeIgnoresAbsolutePath() {
        String path = "/opt/templates";
        assertThat(ImageDef.expandTilde(path)).isEqualTo(path);
    }

    @Test
    void ancestorChainStopsOnCycle() {
        ImageDef a = createDef("a", "b");
        ImageDef b = createDef("b", "a");
        Map<String, ImageDef> defs = Map.of("a", a, "b", b);
        List<ImageDef> ancestors = ImageDef.ancestors(a, defs);

        assertThat(ancestors).hasSize(1);
        assertThat(ancestors.getFirst().name).isEqualTo("b");
    }

    @Test
    void ancestorChainStopsOnSelfReference() {
        ImageDef self = createDef("self", "self");
        Map<String, ImageDef> defs = Map.of("self", self);
        List<ImageDef> ancestors = ImageDef.ancestors(self, defs);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void collectAllPackagesFromLeafOnly() {
        ImageDef root = createDef("root", null);
        ImageDef child = createDefWithPackages("child", "root", List.of("gcc", "make"));
        Map<String, ImageDef> defs = Map.of("root", root, "child", child);

        List<String> packages = ImageDef.collectAllPackages(child, defs);
        assertThat(packages).containsExactly("gcc", "make");
    }

    @Test
    void collectAllPackagesInheritsFromParent() {
        ImageDef root = createDefWithPackages("root", null, List.of("base-pkg"));
        ImageDef child = createDefWithPackages("child", "root", List.of("child-pkg"));
        Map<String, ImageDef> defs = Map.of("root", root, "child", child);

        List<String> packages = ImageDef.collectAllPackages(child, defs);

        assertThat(packages).containsExactly("base-pkg", "child-pkg");
    }

    @Test
    void collectAllPackagesDeduplicates() {
        ImageDef root = createDefWithPackages("root", null, List.of("gcc", "make"));
        ImageDef child = createDefWithPackages("child", "root", List.of("make", "autoconf"));
        Map<String, ImageDef> defs = Map.of("root", root, "child", child);

        List<String> packages = ImageDef.collectAllPackages(child, defs);

        assertThat(packages).containsExactly("gcc", "make", "autoconf");
    }

    @Test
    void collectAllPackagesThreeLevelChain() {
        ImageDef gp = createDefWithPackages("gp", null, List.of("gp-pkg"));
        ImageDef p = createDefWithPackages("parent", "gp", List.of("parent-pkg"));
        ImageDef child = createDefWithPackages("child", "parent", List.of("child-pkg"));
        Map<String, ImageDef> defs = Map.of("gp", gp, "parent", p, "child", child);

        List<String> packages = ImageDef.collectAllPackages(child, defs);
        assertThat(packages).containsExactly("gp-pkg", "parent-pkg", "child-pkg");
    }

    @Test
    void collectAllPackagesBuiltinOpenjdkInheritsMinimal() {
        Map<String, ImageDef> defs = ImageDef.loadAll(List.of(), null);
        ImageDef openjdk = defs.get("tpl-openjdk");
        List<String> packages = ImageDef.collectAllPackages(openjdk, defs);
        assertThat(packages).contains("gcc", "java-latest-openjdk-devel");
    }

    @Test
    void collectAllPackagesRootTemplate() {
        ImageDef root = createDefWithPackages("root", null, List.of("pkg1"));
        Map<String, ImageDef> defs = Map.of("root", root);
        List<String> packages = ImageDef.collectAllPackages(root, defs);
        assertThat(packages).containsExactly("pkg1");
    }

    @Test
    void setPackagesNullDefaultsToEmpty() throws IOException {
        String yaml =
                """
            name: tpl-test
            packages:
            """;
        ImageDef def = ImageDef.parseYaml(yaml);
        assertThat(def.packages)
                .isNotNull()
                .isEmpty();
    }

    private static ImageDef createDef(String name, String parent) {
        ImageDef def = new ImageDef();
        def.name = name;
        def.parent = parent;
        return def;
    }

    private static ImageDef createDefWithPackages(String name, String parent, List<String> packages) {
        ImageDef def = new ImageDef();
        def.name = name;
        def.parent = parent;
        def.packages = packages;
        return def;
    }
}
