package org.mendrugo.attimo.aws;

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
public class AwsConfig {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @JsonProperty("continent")
    private String continent = "";

    @JsonProperty("preferred-region")
    private String preferredRegion = "";

    @JsonProperty("ssh-public-key")
    private String sshPublicKey = "";

    public String getContinent() {
        return continent;
    }

    public void setContinent(String continent) {
        this.continent = continent == null ? "" : continent;
    }

    public String getPreferredRegion() {
        return preferredRegion;
    }

    public void setPreferredRegion(String preferredRegion) {
        this.preferredRegion = preferredRegion == null ? "" : preferredRegion;
    }

    public String getSshPublicKey() {
        return sshPublicKey;
    }

    public void setSshPublicKey(String sshPublicKey) {
        this.sshPublicKey = sshPublicKey == null ? "" : sshPublicKey;
    }

    public static AwsConfig load() {
        final Path configFile = Environment.configFile(Aws.CLOUD);
        if (!Files.exists(configFile)) {
            return new AwsConfig();
        }

        try {
            return YAML.readValue(configFile.toFile(), AwsConfig.class);
        } catch (IOException e) {
            System.err.println("Warning: could not read AWS config: " + e.getMessage());
            return new AwsConfig();
        }
    }

    public void save() {
        final Path configFile = Environment.configFile(Aws.CLOUD);
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
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save AWS config: " + e.getMessage(), e);
        }
    }
}
