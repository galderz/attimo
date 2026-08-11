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

/**
 * Global attimo configuration stored in ~/.config/attimo/config.yaml.
 * Owner-only permissions (chmod 600) for security.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttimoConfig
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @JsonProperty("preferred-region")
    private String preferredRegion = "";

    @JsonProperty("ssh-public-key")
    private String sshPublicKey = "";

    public String getPreferredRegion()
    {
        return preferredRegion;
    }

    public void setPreferredRegion(final String preferredRegion)
    {
        this.preferredRegion = preferredRegion == null ? "" : preferredRegion;
    }

    public String getSshPublicKey()
    {
        return sshPublicKey;
    }

    public void setSshPublicKey(final String sshPublicKey)
    {
        this.sshPublicKey = sshPublicKey == null ? "" : sshPublicKey;
    }

    /**
     * Load config for a specific cloud provider.
     */
    public static AttimoConfig load(final String cloud)
    {
        final Path configFile = Environment.configFile(cloud);
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
            System.err.println("Warning: could not read config: " + e.getMessage());
            return new AttimoConfig();
        }
    }

    /**
     * Save config for a specific cloud provider.
     */
    public void save(final String cloud)
    {
        final Path configFile = Environment.configFile(cloud);
        try
        {
            Files.createDirectories(configFile.getParent());
            final Path tmp = Files.createTempFile(configFile.getParent(), ".attimo-", ".tmp");
            try
            {
                YAML.writeValue(tmp.toFile(), this);
                // Owner-only permissions
                tmp.toFile().setReadable(false, false);
                tmp.toFile().setReadable(true, true);
                tmp.toFile().setWritable(false, false);
                tmp.toFile().setWritable(true, true);
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (final Exception e)
            {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        catch (final IOException e)
        {
            throw new RuntimeException("Failed to save config: " + e.getMessage(), e);
        }
    }
}
