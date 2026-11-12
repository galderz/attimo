package org.mendrugo.attimo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttimoConfigTest
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultSearchPathsEmpty()
    {
        final var config = new AttimoConfig();
        assertThat(config.getSearchPaths()).isEmpty();
    }

    @Test
    void setSearchPaths()
    {
        final var config = new AttimoConfig();
        config.setSearchPaths(List.of("/home/user/templates", "/opt/shared"));
        assertThat(config.getSearchPaths())
            .containsExactly("/home/user/templates", "/opt/shared");
    }

    @Test
    void setNullSearchPathsDefaultsToEmpty()
    {
        final var config = new AttimoConfig();
        config.setSearchPaths(null);
        assertThat(config.getSearchPaths()).isEmpty();
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir final Path tempDir) throws IOException
    {

        final var configFile = tempDir.resolve("config.yaml");
        final var config = new AttimoConfig();
        config.setSearchPaths(List.of("/home/user/templates", "/opt/shared"));
        YAML.writeValue(configFile.toFile(), config);

        final var loaded = YAML.readValue(configFile.toFile(), AttimoConfig.class);
        assertThat(loaded.getSearchPaths())
            .containsExactly("/home/user/templates", "/opt/shared");
    }

    @Test
    void loadFromEmptyFileReturnsDefaults(@TempDir final Path tempDir) throws IOException
    {
        final var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "{}\n");

        final var loaded = YAML.readValue(configFile.toFile(), AttimoConfig.class);
        assertThat(loaded.getSearchPaths()).isEmpty();
    }

    @Test
    void loadIgnoresUnknownFields(@TempDir final Path tempDir) throws IOException
    {
        final var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
            search-paths:
              - /opt/templates
            unknown-field: should be ignored
            """);

        final var loaded = YAML.readValue(configFile.toFile(), AttimoConfig.class);
        assertThat(loaded.getSearchPaths()).containsExactly("/opt/templates");
    }
}
