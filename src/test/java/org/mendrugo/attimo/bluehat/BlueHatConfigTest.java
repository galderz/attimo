package org.mendrugo.attimo.bluehat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BlueHatConfigTest
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsWhenNoFileExists()
    {
        final var config = new BlueHatConfig();
        assertThat(config.getHostName()).isEmpty();
        assertThat(config.getSshPublicKey()).isEmpty();
    }

    @Test
    void roundTripSaveAndLoad(@TempDir final Path tempDir) throws Exception
    {
        final Path configFile = tempDir.resolve("config.yaml");

        final var config = new BlueHatConfig();
        config.setHostName("bluehat-proxy.acme.com");
        config.setSshPublicKey("~/.ssh/id_ed25519.pub");

        YAML.writeValue(configFile.toFile(), config);

        final var loaded = YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        assertThat(loaded.getHostName()).isEqualTo("bluehat-proxy.acme.com");
        assertThat(loaded.getSshPublicKey()).isEqualTo("~/.ssh/id_ed25519.pub");
    }

    @Test
    void nullsSafelyDefaultToEmpty()
    {
        final var config = new BlueHatConfig();
        config.setHostName(null);
        config.setSshPublicKey(null);

        assertThat(config.getHostName()).isEmpty();
        assertThat(config.getSshPublicKey()).isEmpty();
    }

    @Test
    void ignoresUnknownFields(@TempDir final Path tempDir) throws Exception
    {
        final Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            host-name: bluehat-proxy.acme.com
            ssh-public-key: ~/.ssh/id_rsa.pub
            unknown-field: should-be-ignored
            """);

        final var loaded = YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        assertThat(loaded.getHostName()).isEqualTo("bluehat-proxy.acme.com");
        assertThat(loaded.getSshPublicKey()).isEqualTo("~/.ssh/id_rsa.pub");
    }

    @Test
    void parsesEmptyFile(@TempDir final Path tempDir) throws Exception
    {
        final Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "---\n");

        final var loaded = YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        if (loaded != null)
        {
            assertThat(loaded.getHostName()).isEmpty();
            assertThat(loaded.getSshPublicKey()).isEmpty();
        }
    }
}
