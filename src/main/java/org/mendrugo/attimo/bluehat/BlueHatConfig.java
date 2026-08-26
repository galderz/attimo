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
 * <p>During {@code ato bh init}, the user chooses one of two modes:
 * <ul>
 *   <li><b>Local mode</b> — provides a git {@code repository} URL.
 *       The repository is cloned and built during init; the cloud is
 *       started locally on each command and stopped when it completes.</li>
 *   <li><b>Remote mode</b> — provides a {@code host-name} of a running
 *       Blue Hat cloud. Commands connect directly to that host.</li>
 * </ul>
 *
 * <p>The two modes are mutually exclusive: exactly one of {@code repository}
 * or {@code host-name} is set.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlueHatConfig
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @JsonProperty("repository")
    private String repository = "";

    @JsonProperty("host-name")
    private String hostName = "";

    @JsonProperty("ssh-public-key")
    private String sshPublicKey = "";

    public String getRepository()
    {
        return repository;
    }

    public void setRepository(final String repository)
    {
        this.repository = repository == null ? "" : repository;
    }

    public String getHostName()
    {
        return hostName;
    }

    public void setHostName(final String hostName)
    {
        this.hostName = hostName == null ? "" : hostName;
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
     * Whether the Blue Hat cloud runs locally (a repository is configured).
     */
    public boolean isLocal()
    {
        return !repository.isBlank();
    }

    /**
     * Whether the configuration has a cloud target (either repository or host-name).
     */
    public boolean hasCloudTarget()
    {
        return !repository.isBlank() || !hostName.isBlank();
    }

    /**
     * Returns the effective host name for API calls.
     * Local mode uses "localhost"; remote mode uses the configured host-name.
     */
    public String effectiveHostName()
    {
        return isLocal() ? "localhost" : hostName;
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
