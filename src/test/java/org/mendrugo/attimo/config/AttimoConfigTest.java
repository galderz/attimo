package org.mendrugo.attimo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttimoConfigTest
{
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
}
