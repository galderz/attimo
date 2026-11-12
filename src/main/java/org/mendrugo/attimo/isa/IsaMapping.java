package org.mendrugo.attimo.isa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.mendrugo.attimo.Environment;

public class IsaMapping {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String[] BUILTIN_FILES = {"x86_64.yaml", "aarch64.yaml"};

    private final Map<String, IsaFeature> features = new LinkedHashMap<>();

    public IsaMapping() {
        loadBuiltins();
        loadUserOverrides();
    }

    public IsaFeature resolve(String featureName) {
        return features.get(featureName.toLowerCase());
    }

    public List<String> allFeatureNames() {
        return List.copyOf(features.keySet());
    }

    public List<IsaFeature> allFeatures() {
        return List.copyOf(features.values());
    }

    private void loadBuiltins() {
        for (String filename : BUILTIN_FILES) {
            try (InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("isa-mappings/" + filename)) {
                if (is != null) {
                    loadFromStream(is);
                }
            } catch (IOException e) {
                System.err.println("Warning: could not load ISA mapping " + filename + ": " + e.getMessage());
            }
        }
    }

    private void loadUserOverrides() {
        Path userDir = Environment.configRoot().resolve("isa-mappings");
        if (!Files.isDirectory(userDir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*.yaml")) {
            for (Path file : stream) {
                try (InputStream is = Files.newInputStream(file)) {
                    loadFromStream(is);
                } catch (IOException e) {
                    System.err.println("Warning: could not load ISA mapping " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read ISA mappings dir: " + e.getMessage());
        }
    }

    private void loadFromStream(InputStream is) throws IOException {
        MappingFile mappingFile = YAML.readValue(is, MappingFile.class);
        if (mappingFile == null || mappingFile.features == null) {
            return;
        }

        String arch = mappingFile.architecture != null ? mappingFile.architecture : "unknown";
        for (Entry<String, FeatureEntry> entry : mappingFile.features.entrySet()) {
            String name = entry.getKey().toLowerCase();
            FeatureEntry fe = entry.getValue();
            features.put(
                    name,
                    new IsaFeature(
                            name,
                            fe.description != null ? fe.description : "",
                            arch,
                            fe.families != null ? List.copyOf(fe.families) : List.of()));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MappingFile {
        public String architecture;
        public Map<String, FeatureEntry> features;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FeatureEntry {
        public String description;
        public List<String> families;
    }
}
