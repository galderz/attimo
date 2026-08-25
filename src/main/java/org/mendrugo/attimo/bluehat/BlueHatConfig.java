package org.mendrugo.attimo.bluehat;

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
 * Blue Hat-specific configuration stored at ~/.config/attimo/bh/config.yaml.
 * Owner-only permissions (chmod 600) for security.
 *
 * <p>The Blue Hat host name is no longer stored here — it is managed
 * via Quarkus configuration ({@code attimo.bluehat.host-name}).
 * See {@link BlueHatSettings}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlueHatConfig
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @JsonProperty("ssh-public-key")
    private String sshPublicKey = "";

    public String getSshPublicKey()
    {
        return sshPublicKey;
    }

    public void setSshPublicKey(final String sshPublicKey)
    {
        this.sshPublicKey = sshPublicKey == null ? "" : sshPublicKey;
    }

    public static BlueHatConfig load()
    {
        final Path configFile = Environment.configFile(BlueHat.CLOUD);
        if (!Files.exists(configFile))
        {
            return new BlueHatConfig();
        }

        try
        {
            return YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        }
        catch (final IOException e)
        {
            System.err.println("Warning: could not read Blue Hat config: " + e.getMessage());
            return new BlueHatConfig();
        }
    }

    public void save()
    {
        final Path configFile = Environment.configFile(BlueHat.CLOUD);
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
            throw new RuntimeException("Failed to save Blue Hat config: " + e.getMessage(), e);
        }
    }
}
