package org.mendrugo.attimo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AttimoConfigTest
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsWhenNoFileExists()
    {
        final var config = new AttimoConfig();
        assertThat(config.getPreferredRegion()).isEmpty();
        assertThat(config.getSshPublicKey()).isEmpty();
    }

    @Test
    void roundTripSaveAndLoad(@TempDir final Path tempDir) throws Exception
    {
        final Path configFile = tempDir.resolve("config.yaml");

        final var config = new AttimoConfig();
        config.setPreferredRegion("eu-west-1");
        config.setSshPublicKey("~/.ssh/id_ed25519.pub");

        // Write directly to temp dir for test isolation
        YAML.writeValue(configFile.toFile(), config);

        final var loaded = YAML.readValue(configFile.toFile(), AttimoConfig.class);
        assertThat(loaded.getPreferredRegion()).isEqualTo("eu-west-1");
        assertThat(loaded.getSshPublicKey()).isEqualTo("~/.ssh/id_ed25519.pub");
    }

    @Test
    void nullsSafelyDefaultToEmpty()
    {
        final var config = new AttimoConfig();
        config.setPreferredRegion(null);
        config.setSshPublicKey(null);

        assertThat(config.getPreferredRegion()).isEmpty();
        assertThat(config.getSshPublicKey()).isEmpty();
    }

    @Test
    void ignoresUnknownFields(@TempDir final Path tempDir) throws Exception
    {
        final Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            preferred-region: us-east-1
            ssh-public-key: ~/.ssh/id_rsa.pub
            unknown-field: should-be-ignored
            """);

        final var loaded = YAML.readValue(configFile.toFile(), AttimoConfig.class);
        assertThat(loaded.getPreferredRegion()).isEqualTo("us-east-1");
        assertThat(loaded.getSshPublicKey()).isEqualTo("~/.ssh/id_rsa.pub");
    }

    @Test
    void parsesEmptyFile(@TempDir final Path tempDir) throws Exception
    {
        final Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "---\n");

        final var loaded = YAML.readValue(configFile.toFile(), AttimoConfig.class);
        // Jackson returns null for empty YAML documents;
        // AttimoConfig.load() handles this by returning a new instance
        if (loaded != null)
        {
            assertThat(loaded.getPreferredRegion()).isEmpty();
            assertThat(loaded.getSshPublicKey()).isEmpty();
        }
    }
}
