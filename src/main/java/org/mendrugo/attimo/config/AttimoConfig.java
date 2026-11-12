package org.mendrugo.attimo.config;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.mendrugo.attimo.Environment;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttimoConfig {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @JsonProperty("search-paths")
    private List<String> searchPaths = List.of();

    public List<String> getSearchPaths() {
        return searchPaths;
    }

    public void setSearchPaths(final List<String> searchPaths) {
        this.searchPaths = searchPaths == null ? List.of() : searchPaths;
    }

    public static AttimoConfig load() {
        final Path configFile = Environment.configRoot().resolve("config.yaml");
        if (!Files.exists(configFile)) {
            return new AttimoConfig();
        }

        try {
            return YAML.readValue(configFile.toFile(), AttimoConfig.class);
        } catch (final IOException e) {
            System.err.println("Warning: could not read attimo config: " + e.getMessage());
            return new AttimoConfig();
        }
    }

    public void save() {
        final Path configFile = Environment.configRoot().resolve("config.yaml");
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
            throw new RuntimeException("Failed to save attimo config: " + e.getMessage(), e);
        }
    }

    public static void setupSearchPaths(final Console console) {
        System.out.println("  Local directories where attimo looks for custom image");
        System.out.println("  definitions. Each directory should contain an images/");
        System.out.println("  subdirectory with YAML template files.");
        System.out.println();

        final var config = load();
        final var existing = config.getSearchPaths();

        if (!existing.isEmpty()) {
            System.out.println("  Current search paths:");
            for (int i = 0; i < existing.size(); i++) {
                System.out.println("    " + (i + 1) + ". " + existing.get(i));
            }
        }

        if (console == null) {
            if (existing.isEmpty()) {
                System.out.println("  No search paths configured." + " Add them later in ~/.config/attimo/config.yaml");
            }
            return;
        }

        final var paths = new ArrayList<>(existing);
        while (true) {
            final var hasEntries = !paths.isEmpty();
            System.out.print("  Add a local directory"
                    + (hasEntries ? " or # to remove" : "")
                    + " (or press Enter to "
                    + (hasEntries ? "finish" : "skip")
                    + "): ");
            final var input = console.readLine().strip();
            if (input.isEmpty()) {
                break;
            }

            if (input.matches("\\d{1,2}")) {
                final int index = Integer.parseInt(input) - 1;
                if (index >= 0 && index < paths.size()) {
                    System.out.println("  Removed: " + paths.remove(index));
                } else {
                    System.out.println("  No entry #" + input + ".");
                }
                continue;
            }

            final var expanded = ImageDef.expandTilde(input);
            final var path = Path.of(expanded)
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            if (paths.contains(path)) {
                System.out.println("  Already in the list.");
            } else {
                paths.add(path);
                System.out.println("  Added: " + path);
            }
        }

        if (!paths.equals(existing)) {
            config.setSearchPaths(paths);
            config.save();
            System.out.println("  Search paths saved.");
        } else if (paths.isEmpty()) {
            System.out.println("  No search paths configured." + " Add them later in ~/.config/attimo/config.yaml");
        } else {
            System.out.println("  Paths unchanged.");
        }
    }
}
