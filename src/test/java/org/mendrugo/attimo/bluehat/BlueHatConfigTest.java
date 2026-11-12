package org.mendrugo.attimo.bluehat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BlueHatConfigTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsWhenNoFileExists() {
        BlueHatConfig config = new BlueHatConfig();
        assertThat(config.repository).isEmpty();
        assertThat(config.hostName).isEmpty();
        assertThat(config.sshPublicKey).isEmpty();
    }

    @Test
    void roundTripWithRepository(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");

        BlueHatConfig config = new BlueHatConfig();
        config.repository = "https://github.com/openjdk/bluehat-cloud.git";
        config.sshPublicKey = "~/.ssh/id_ed25519.pub";

        YAML.writeValue(configFile.toFile(), config);

        BlueHatConfig loaded = YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        assertThat(loaded.repository).isEqualTo("https://github.com/openjdk/bluehat-cloud.git");
        assertThat(loaded.hostName).isEmpty();
        assertThat(loaded.sshPublicKey).isEqualTo("~/.ssh/id_ed25519.pub");
    }

    @Test
    void roundTripWithHostName(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");

        BlueHatConfig config = new BlueHatConfig();
        config.hostName = "bluehat-proxy.acme.com";
        config.sshPublicKey = "~/.ssh/id_rsa.pub";

        YAML.writeValue(configFile.toFile(), config);

        BlueHatConfig loaded = YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        assertThat(loaded.repository).isEmpty();
        assertThat(loaded.hostName).isEqualTo("bluehat-proxy.acme.com");
        assertThat(loaded.sshPublicKey).isEqualTo("~/.ssh/id_rsa.pub");
    }

    @Test
    void isLocalWhenRepositoryIsSet() {
        BlueHatConfig config = new BlueHatConfig();
        config.repository = "https://github.com/openjdk/bluehat-cloud.git";
        assertThat(config.isLocal()).isTrue();
        assertThat(config.hasCloudTarget()).isTrue();
        assertThat(config.effectiveHostName()).isEqualTo("localhost");
    }

    @Test
    void isRemoteWhenHostNameIsSet() {
        BlueHatConfig config = new BlueHatConfig();
        config.hostName = "bluehat-proxy.acme.com";
        assertThat(config.isLocal()).isFalse();
        assertThat(config.hasCloudTarget()).isTrue();
        assertThat(config.effectiveHostName()).isEqualTo("bluehat-proxy.acme.com");
    }

    @Test
    void hasNoCloudTargetWhenEmpty() {
        BlueHatConfig config = new BlueHatConfig();
        assertThat(config.isLocal()).isFalse();
        assertThat(config.hasCloudTarget()).isFalse();
    }

    @Test
    void ignoresUnknownFields(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile,
                """
            repository: https://github.com/openjdk/bluehat-cloud.git
            ssh-public-key: ~/.ssh/id_rsa.pub
            unknown-field: should-be-ignored
            """);

        BlueHatConfig loaded = YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        assertThat(loaded.repository).isEqualTo("https://github.com/openjdk/bluehat-cloud.git");
        assertThat(loaded.sshPublicKey).isEqualTo("~/.ssh/id_rsa.pub");
    }

    @Test
    void parsesEmptyFile(@TempDir final Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "---\n");

        BlueHatConfig loaded = YAML.readValue(configFile.toFile(), BlueHatConfig.class);
        if (loaded != null) {
            assertThat(loaded.repository).isEmpty();
            assertThat(loaded.hostName).isEmpty();
            assertThat(loaded.sshPublicKey).isEmpty();
        }
    }
}
