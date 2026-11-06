package org.mendrugo.attimo.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.mendrugo.attimo.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Shared attimo configuration stored at {@code ~/.config/attimo/config.yaml}.
 *
 * <p>Contains settings shared across all cloud providers, such as template
 * search paths. Cloud-specific settings live in per-cloud config files
 * (e.g. {@code ~/.config/attimo/aws/config.yaml}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttimoConfig
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @JsonProperty("search-paths")
    private List<String> searchPaths = List.of();

    public List<String> getSearchPaths()
    {
        return searchPaths;
    }

    public void setSearchPaths(final List<String> searchPaths)
    {
        this.searchPaths = searchPaths == null ? List.of() : searchPaths;
    }

    /**
     * Load from {@code ~/.config/attimo/config.yaml}, or return defaults.
     */
    public static AttimoConfig load()
    {
        final Path configFile = Environment.configRoot().resolve("config.yaml");
        if (!Files.exists(configFile))
        {
            return new AttimoConfig();
        }

        try
        {
            return YAML.readValue(configFile.toFile(), AttimoConfig.class);
        }
        catch (final IOException e)
        {
            System.err.println("Warning: could not read attimo config: " + e.getMessage());
            return new AttimoConfig();
        }
    }

    /**
     * Save to {@code ~/.config/attimo/config.yaml} with owner-only permissions.
     */
    public void save()
    {
        final Path configFile = Environment.configRoot().resolve("config.yaml");
        try
        {
            Files.createDirectories(configFile.getParent());
            final Path tmp = Files.createTempFile(configFile.getParent(), ".attimo-", ".tmp");
            try
            {
                YAML.writeValue(tmp.toFile(), this);
                tmp.toFile().setReadable(false, false);
                tmp.toFile().setReadable(true, true);
                tmp.toFile().setWritable(false, false);
                tmp.toFile().setWritable(true, true);
                Files.move(tmp, configFile
                    , StandardCopyOption.REPLACE_EXISTING
                    , StandardCopyOption.ATOMIC_MOVE
                );
            }
            catch (final Exception e)
            {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        catch (final IOException e)
        {
            throw new RuntimeException("Failed to save attimo config: " + e.getMessage(), e);
        }
    }
}
