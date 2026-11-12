package org.mendrugo.attimo.aws;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AwsConfigTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsWhenNoFileExists() {
        AwsConfig config = new AwsConfig();
        assertThat(config.getContinent()).isEmpty();
        assertThat(config.getPreferredRegion()).isEmpty();
        assertThat(config.getSshPublicKey()).isEmpty();
    }

    @Test
    void roundTripSaveAndLoad(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");

        AwsConfig config = new AwsConfig();
        config.setContinent("EMEA");
        config.setPreferredRegion("eu-west-1");
        config.setSshPublicKey("~/.ssh/id_ed25519.pub");

        YAML.writeValue(configFile.toFile(), config);

        AwsConfig loaded = YAML.readValue(configFile.toFile(), AwsConfig.class);
        assertThat(loaded.getContinent()).isEqualTo("EMEA");
        assertThat(loaded.getPreferredRegion()).isEqualTo("eu-west-1");
        assertThat(loaded.getSshPublicKey()).isEqualTo("~/.ssh/id_ed25519.pub");
    }

    @Test
    void nullsSafelyDefaultToEmpty() {
        AwsConfig config = new AwsConfig();
        config.setContinent(null);
        config.setPreferredRegion(null);
        config.setSshPublicKey(null);

        assertThat(config.getContinent()).isEmpty();
        assertThat(config.getPreferredRegion()).isEmpty();
        assertThat(config.getSshPublicKey()).isEmpty();
    }

    @Test
    void ignoresUnknownFields(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile,
                """
            preferred-region: us-east-1
            ssh-public-key: ~/.ssh/id_rsa.pub
            unknown-field: should-be-ignored
            """);

        AwsConfig loaded = YAML.readValue(configFile.toFile(), AwsConfig.class);
        assertThat(loaded.getPreferredRegion()).isEqualTo("us-east-1");
        assertThat(loaded.getSshPublicKey()).isEqualTo("~/.ssh/id_rsa.pub");
    }

    @Test
    void parsesEmptyFile(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "---\n");

        AwsConfig loaded = YAML.readValue(configFile.toFile(), AwsConfig.class);
        if (loaded != null) {
            assertThat(loaded.getContinent()).isEmpty();
            assertThat(loaded.getPreferredRegion()).isEmpty();
            assertThat(loaded.getSshPublicKey()).isEmpty();
        }
    }

    @Test
    void backwardCompatWithoutContinentField(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile,
                """
            preferred-region: eu-west-1
            ssh-public-key: ~/.ssh/id_ed25519.pub
            """);

        AwsConfig loaded = YAML.readValue(configFile.toFile(), AwsConfig.class);
        assertThat(loaded.getContinent()).isEmpty();
        assertThat(loaded.getPreferredRegion()).isEqualTo("eu-west-1");
    }
}
