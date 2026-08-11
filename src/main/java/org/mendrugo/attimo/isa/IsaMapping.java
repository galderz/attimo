package org.mendrugo.attimo.isa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.mendrugo.attimo.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid ISA → instance family mapping.
 * Static YAML mappings from classpath + user overrides from ~/.config/attimo/isa-mappings/.
 * Falls back to AWS DescribeInstanceTypes for unknown features (not yet implemented).
 */
public class IsaMapping
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String[] BUILTIN_FILES = { "x86_64.yaml", "aarch64.yaml" };

    private final Map<String, IsaFeature> features = new LinkedHashMap<>();

    public IsaMapping()
    {
        loadBuiltins();
        loadUserOverrides();
    }

    /**
     * Resolve an ISA feature name to its definition.
     *
     * @param featureName the ISA feature (e.g., "avx512", "sve")
     * @return the feature definition, or null if not found
     */
    public IsaFeature resolve(final String featureName)
    {
        return features.get(featureName.toLowerCase());
    }

    /**
     * Get all known ISA feature names.
     */
    public List<String> allFeatureNames()
    {
        return List.copyOf(features.keySet());
    }

    /**
     * Get all known features.
     */
    public List<IsaFeature> allFeatures()
    {
        return List.copyOf(features.values());
    }

    private void loadBuiltins()
    {
        for (final String filename : BUILTIN_FILES)
        {
            try (final InputStream is = getClass().getClassLoader()
                .getResourceAsStream("isa-mappings/" + filename))
            {
                if (is != null)
                {
                    loadFromStream(is);
                }
            }
            catch (final IOException e)
            {
                System.err.println("Warning: could not load ISA mapping " + filename + ": " + e.getMessage());
            }
        }
    }

    private void loadUserOverrides()
    {
        final Path userDir = Environment.configRoot().resolve("isa-mappings");
        if (!Files.isDirectory(userDir))
        {
            return;
        }

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*.yaml"))
        {
            for (final Path file : stream)
            {
                try (final InputStream is = Files.newInputStream(file))
                {
                    loadFromStream(is);
                }
                catch (final IOException e)
                {
                    System.err.println("Warning: could not load ISA mapping " + file + ": " + e.getMessage());
                }
            }
        }
        catch (final IOException e)
        {
            System.err.println("Warning: could not read ISA mappings dir: " + e.getMessage());
        }
    }

    private void loadFromStream(final InputStream is) throws IOException
    {
        final MappingFile mappingFile = YAML.readValue(is, MappingFile.class);
        if (mappingFile == null || mappingFile.features == null)
        {
            return;
        }

        final String arch = mappingFile.architecture != null ? mappingFile.architecture : "unknown";
        for (final var entry : mappingFile.features.entrySet())
        {
            final String name = entry.getKey().toLowerCase();
            final FeatureEntry fe = entry.getValue();
            features.put(
                name
                , new IsaFeature(
                    name
                    , fe.description != null ? fe.description : ""
                    , arch
                    , fe.families != null ? List.copyOf(fe.families) : List.of()
                )
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MappingFile
    {
        public String architecture;
        public Map<String, FeatureEntry> features;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FeatureEntry
    {
        public String description;
        public List<String> families;
    }
}
