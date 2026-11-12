package org.mendrugo.attimo.bluehat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.mendrugo.attimo.Environment;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlueHatConfig {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @JsonProperty("repository")
    private String repository = "";

    @JsonProperty("host-name")
    private String hostName = "";

    @JsonProperty("ssh-public-key")
    private String sshPublicKey = "";

    public String getRepository() {
        return repository;
    }

    public void setRepository(final String repository) {
        this.repository = repository == null ? "" : repository;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(final String hostName) {
        this.hostName = hostName == null ? "" : hostName;
    }

    public String getSshPublicKey() {
        return sshPublicKey;
    }

    public void setSshPublicKey(final String sshPublicKey) {
        this.sshPublicKey = sshPublicKey == null ? "" : sshPublicKey;
    }

    public boolean isLocal() {
        return !repository.isBlank();
    }

    public boolean hasCloudTarget() {
        return !repository.isBlank() || !hostName.isBlank();
    }

    public String effectiveHostName() {
        return isLocal() ? "localhost" : hostName;
    }

    public static BlueHatConfig load() {
        final Path configFile = Environment.configFile(BlueHat.CLOUD);
        if (!Files.exists(configFile)) {
            return new BlueHatConfig();
        }

        try {
            return YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        } catch (final IOException e) {
            System.err.println("Warning: could not read Blue Hat config: " + e.getMessage());
            return new BlueHatConfig();
        }
    }

    public void save() {
        final Path configFile = Environment.configFile(BlueHat.CLOUD);
        try {
            Files.createDirectories(configFile.getParent());
            final Path tmp = Files.createTempFile(configFile.getParent(), ".attimo-", ".tmp");
            try {
                YAML.writeValue(tmp.toFile(), this);
                tmp.toFile().setReadable(false, false);
                tmp.toFile().setReadable(true, true);
                tmp.toFile().setWritable(false, false);
                tmp.toFile().setWritable(true, true);
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (final IOException e) {
            throw new RuntimeException("Failed to save Blue Hat config: " + e.getMessage(), e);
        }
    }
}
