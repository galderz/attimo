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
import java.time.Instant;

/**
 * Tracks the active spot instance for reconnection after local restarts.
 * Stored at ~/.config/attimo/state.yaml.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceState
{
    @JsonProperty("instance-id")
    private String instanceId = "";

    @JsonProperty("region")
    private String region = "";

    @JsonProperty("availability-zone")
    private String availabilityZone = "";

    @JsonProperty("instance-type")
    private String instanceType = "";

    @JsonProperty("public-ip")
    private String publicIp = "";

    @JsonProperty("launched-at")
    private String launchedAt = "";

    @JsonProperty("spot-price")
    private double spotPrice;

    @JsonProperty("template")
    private String template = "";

    @JsonProperty("isa-feature")
    private String isaFeature = "";

    @JsonProperty("ami-id")
    private String amiId = "";

    @JsonProperty("security-group-id")
    private String securityGroupId = "";

    @JsonProperty("key-pair-name")
    private String keyPairName = "";

    @JsonProperty("session-id")
    private String sessionId = "";

    // Getters and setters

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(final String instanceId) { this.instanceId = instanceId == null ? "" : instanceId; }

    public String getRegion() { return region; }
    public void setRegion(final String region) { this.region = region == null ? "" : region; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(final String az) { this.availabilityZone = az == null ? "" : az; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(final String instanceType) { this.instanceType = instanceType == null ? "" : instanceType; }

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(final String publicIp) { this.publicIp = publicIp == null ? "" : publicIp; }

    public String getLaunchedAt() { return launchedAt; }
    public void setLaunchedAt(final String launchedAt) { this.launchedAt = launchedAt == null ? "" : launchedAt; }

    public double getSpotPrice() { return spotPrice; }
    public void setSpotPrice(final double spotPrice) { this.spotPrice = spotPrice; }

    public String getTemplate() { return template; }
    public void setTemplate(final String template) { this.template = template == null ? "" : template; }

    public String getIsaFeature() { return isaFeature; }
    public void setIsaFeature(final String isaFeature) { this.isaFeature = isaFeature == null ? "" : isaFeature; }

    public String getAmiId() { return amiId; }
    public void setAmiId(final String amiId) { this.amiId = amiId == null ? "" : amiId; }

    public String getSecurityGroupId() { return securityGroupId; }
    public void setSecurityGroupId(final String sgId) { this.securityGroupId = sgId == null ? "" : sgId; }

    public String getKeyPairName() { return keyPairName; }
    public void setKeyPairName(final String keyPairName) { this.keyPairName = keyPairName == null ? "" : keyPairName; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(final String sessionId) { this.sessionId = sessionId == null ? "" : sessionId; }

    public boolean hasActiveInstance()
    {
        return !instanceId.isBlank();
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static InstanceState load()
    {
        final Path stateFile = Environment.stateFile();
        if (!Files.exists(stateFile))
        {
            return new InstanceState();
        }

        try
        {
            return YAML.readValue(stateFile.toFile(), InstanceState.class);
        }
        catch (final IOException e)
        {
            System.err.println("Warning: could not read state: " + e.getMessage());
            return new InstanceState();
        }
    }

    public void save()
    {
        final Path stateFile = Environment.stateFile();
        try
        {
            Files.createDirectories(stateFile.getParent());
            final Path tmp = Files.createTempFile(stateFile.getParent(), ".attimo-state-", ".tmp");
            try
            {
                YAML.writeValue(tmp.toFile(), this);
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (final Exception e)
            {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        catch (final IOException e)
        {
            throw new RuntimeException("Failed to save state: " + e.getMessage(), e);
        }
    }

    public static void clear()
    {
        try
        {
            Files.deleteIfExists(Environment.stateFile());
        }
        catch (final IOException e)
        {
            System.err.println("Warning: could not clear state: " + e.getMessage());
        }
    }
}
