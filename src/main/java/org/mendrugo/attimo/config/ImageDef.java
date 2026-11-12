package org.mendrugo.attimo.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageDef {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String RESOURCE_DIR = "images/";

    private static final List<String> BUILTIN_FILES = List.of("minimal.yaml", "openjdk.yaml");

    private static final Path PROJECT_IMAGES_DIR = Path.of(".attimo/images");

    private String name;
    private String description = "";
    private String parent;
    private List<String> packages = List.of();

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(final String parent) {
        this.parent = parent;
    }

    public List<String> getPackages() {
        return packages;
    }

    public void setPackages(final List<String> packages) {
        this.packages = packages == null ? List.of() : packages;
    }

    public boolean isRoot() {
        return parent == null || parent.isBlank();
    }

    public static List<ImageDef> ancestors(final ImageDef start, final Map<String, ImageDef> defs) {
        final var result = new ArrayList<ImageDef>();
        final var visited = new HashSet<String>();
        visited.add(start.getName());
        var parentName = start.getParent();
        while (parentName != null && !parentName.isBlank()) {
            if (!visited.add(parentName)) {
                break;
            }
            final var p = defs.get(parentName);
            if (p == null) {
                break;
            }
            result.add(p);
            parentName = p.getParent();
        }
        return result;
    }

    public static List<String> collectAllPackages(final ImageDef imageDef, final Map<String, ImageDef> defs) {
        final var ancestors = ancestors(imageDef, defs);

        final var chain = new ArrayList<ImageDef>();
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            chain.add(ancestors.get(i));
        }
        chain.add(imageDef);

        final var allPackages = new LinkedHashSet<String>();
        for (final var def : chain) {
            allPackages.addAll(def.getPackages());
        }
        return new ArrayList<>(allPackages);
    }

    public static ResolvedTemplate resolve(final String templateName) {
        final var defs = loadAll();
        final var imageDef = findByName(templateName, defs);
        if (imageDef == null) {
            System.err.println("Error: unknown template '" + templateName + "'.");
            System.err.println("Available templates: " + String.join(", ", defs.keySet()));
            return null;
        }
        final var packages = collectAllPackages(imageDef, defs);
        return new ResolvedTemplate(imageDef, packages);
    }

    public record ResolvedTemplate(ImageDef imageDef, List<String> packages) {}

    public static Map<String, ImageDef> loadAll() {
        return loadAll(AttimoConfig.load().getSearchPaths());
    }

    public static Map<String, ImageDef> loadAll(final List<String> searchPaths) {
        return loadAll(searchPaths, PROJECT_IMAGES_DIR);
    }

    static Map<String, ImageDef> loadAll(final List<String> searchPaths, final Path projectDir) {
        final var defs = new LinkedHashMap<String, ImageDef>();
        loadBuiltins(defs);
        for (final var searchPath : searchPaths) {
            final var expanded = expandTilde(searchPath);
            loadFromDirectory(Path.of(expanded).resolve("images"), defs);
        }
        if (projectDir != null) {
            loadFromDirectory(projectDir, defs);
        }
        return defs;
    }

    private static void loadBuiltins(final Map<String, ImageDef> defs) {
        for (final var filename : BUILTIN_FILES) {
            final var def = loadResource(RESOURCE_DIR + filename);
            if (def != null) {
                defs.put(def.getName(), def);
            }
        }
    }

    public static ImageDef findByName(final String name, final Map<String, ImageDef> defs) {
        return defs.get(name);
    }

    public static ImageDef parseYaml(final String yaml) throws IOException {
        return YAML.readValue(yaml, ImageDef.class);
    }

    private static void loadFromDirectory(final Path dir, final Map<String, ImageDef> defs) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (final var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                  .sorted()
                  .forEach(path -> {
                      try (final var is = Files.newInputStream(path)) {
                          final var def = YAML.readValue(is, ImageDef.class);
                          if (def.getName() != null) {
                              defs.put(def.getName(), def);
                          }
                      } catch (final IOException e) {
                          System.err.println("Warning: failed to parse " + path.getFileName() + ": " + e.getMessage());
                      }
                  });
        } catch (final IOException e) {
            System.err.println("Warning: failed to scan " + dir + ": " + e.getMessage());
        }
    }

    private static ImageDef loadResource(final String resourcePath) {
        try (final InputStream is = ImageDef.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            return YAML.readValue(is, ImageDef.class);
        } catch (final IOException e) {
            System.err.println("Failed to load image definition: " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    public static String expandTilde(final String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
