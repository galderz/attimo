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
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class ImageDef {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String RESOURCE_DIR = "images/";

    private static final List<String> BUILTIN_FILES = List.of("minimal.yaml", "openjdk.yaml");

    private static final Path PROJECT_IMAGES_DIR = Path.of(".attimo/images");

    String name;
    String description = "";
    String parent;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    List<String> packages = List.of();

    public boolean isRoot() {
        return parent == null || parent.isBlank();
    }

    public static List<ImageDef> ancestors(ImageDef start, Map<String, ImageDef> defs) {
        ArrayList<ImageDef> result = new ArrayList<ImageDef>();
        HashSet<String> visited = new HashSet<String>();
        visited.add(start.name);
        String parentName = start.parent;
        while (parentName != null && !parentName.isBlank()) {
            if (!visited.add(parentName)) {
                break;
            }
            ImageDef p = defs.get(parentName);
            if (p == null) {
                break;
            }
            result.add(p);
            parentName = p.parent;
        }
        return result;
    }

    public static List<String> collectAllPackages(ImageDef imageDef, Map<String, ImageDef> defs) {
        List<ImageDef> ancestors = ancestors(imageDef, defs);

        ArrayList<ImageDef> chain = new ArrayList<ImageDef>();
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            chain.add(ancestors.get(i));
        }
        chain.add(imageDef);

        LinkedHashSet<String> allPackages = new LinkedHashSet<String>();
        for (ImageDef def : chain) {
            allPackages.addAll(def.packages);
        }
        return new ArrayList<>(allPackages);
    }

    public static ResolvedTemplate resolve(String templateName) {
        Map<String, ImageDef> defs = loadAll();
        ImageDef imageDef = findByName(templateName, defs);
        if (imageDef == null) {
            System.err.println("Error: unknown template '" + templateName + "'.");
            System.err.println("Available templates: " + String.join(", ", defs.keySet()));
            return null;
        }
        List<String> packages = collectAllPackages(imageDef, defs);
        return new ResolvedTemplate(imageDef, packages);
    }

    public record ResolvedTemplate(ImageDef imageDef, List<String> packages) {}

    public static Map<String, ImageDef> loadAll() {
        return loadAll(AttimoConfig.load().searchPaths);
    }

    public static Map<String, ImageDef> loadAll(List<String> searchPaths) {
        return loadAll(searchPaths, PROJECT_IMAGES_DIR);
    }

    static Map<String, ImageDef> loadAll(List<String> searchPaths, Path projectDir) {
        LinkedHashMap<String, ImageDef> defs = new LinkedHashMap<String, ImageDef>();
        loadBuiltins(defs);
        for (String searchPath : searchPaths) {
            String expanded = expandTilde(searchPath);
            loadFromDirectory(Path.of(expanded).resolve("images"), defs);
        }
        if (projectDir != null) {
            loadFromDirectory(projectDir, defs);
        }
        return defs;
    }

    private static void loadBuiltins(Map<String, ImageDef> defs) {
        for (String filename : BUILTIN_FILES) {
            ImageDef def = loadResource(RESOURCE_DIR + filename);
            if (def != null) {
                defs.put(def.name, def);
            }
        }
    }

    public static ImageDef findByName(String name, Map<String, ImageDef> defs) {
        return defs.get(name);
    }

    public static ImageDef parseYaml(String yaml) throws IOException {
        return YAML.readValue(yaml, ImageDef.class);
    }

    private static void loadFromDirectory(Path dir, Map<String, ImageDef> defs) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                  .sorted()
                  .forEach(path -> {
                      try (InputStream is = Files.newInputStream(path)) {
                          ImageDef def = YAML.readValue(is, ImageDef.class);
                          if (def.name != null) {
                              defs.put(def.name, def);
                          }
                      } catch (IOException e) {
                          System.err.println("Warning: failed to parse " + path.getFileName() + ": " + e.getMessage());
                      }
                  });
        } catch (IOException e) {
            System.err.println("Warning: failed to scan " + dir + ": " + e.getMessage());
        }
    }

    private static ImageDef loadResource(String resourcePath) {
        try (InputStream is = ImageDef.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            return YAML.readValue(is, ImageDef.class);
        } catch (IOException e) {
            System.err.println("Failed to load image definition: " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    public static String expandTilde(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
