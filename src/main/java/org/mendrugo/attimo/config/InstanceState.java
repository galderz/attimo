package org.mendrugo.attimo.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.mendrugo.attimo.Environment;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceState {
    @JsonProperty("instance-id")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String instanceId = "";

    @JsonProperty("region")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String region = "";

    @JsonProperty("availability-zone")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String availabilityZone = "";

    @JsonProperty("instance-type")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String instanceType = "";

    @JsonProperty("public-ip")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String publicIp = "";

    @JsonProperty("launched-at")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String launchedAt = "";

    @JsonProperty("spot-price")
    public double spotPrice;

    @JsonProperty("template")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String template = "";

    @JsonProperty("isa-feature")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String isaFeature = "";

    @JsonProperty("ami-id")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String amiId = "";

    @JsonProperty("security-group-id")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String securityGroupId = "";

    @JsonProperty("key-pair-name")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String keyPairName = "";

    @JsonProperty("session-id")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public String sessionId = "";

    public boolean hasActiveInstance() {
        return !instanceId.isBlank();
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static InstanceState load(String cloud) {
        Path stateFile = Environment.stateFile(cloud);
        if (!Files.exists(stateFile)) {
            return new InstanceState();
        }

        try {
            return YAML.readValue(stateFile.toFile(), InstanceState.class);
        } catch (IOException e) {
            System.err.println("Warning: could not read state: " + e.getMessage());
            return new InstanceState();
        }
    }

    public void save(String cloud) {
        Path stateFile = Environment.stateFile(cloud);
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = Files.createTempFile(stateFile.getParent(), ".attimo-state-", ".tmp");
            try {
                YAML.writeValue(tmp.toFile(), this);
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save state: " + e.getMessage(), e);
        }
    }

    public static void clear(String cloud) {
        try {
            Files.deleteIfExists(Environment.stateFile(cloud));
        } catch (IOException e) {
            System.err.println("Warning: could not clear state: " + e.getMessage());
        }
    }
}
