package org.mendrugo.attimo.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of a template image, loaded from YAML.
 *
 * <p>Mirrors incus-spawn's ImageDef: templates form a parent chain
 * where a root template (no parent) is the base, and children
 * add packages on top. Resolution order: built-in (classpath
 * {@code images/}) first.
 *
 * <p>Only four fields are supported: {@code name}, {@code description},
 * {@code parent}, and {@code packages}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageDef
{
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String RESOURCE_DIR = "images/";

    // Hardcoded list of built-in image filenames. Classpath directory
    // scanning is unreliable in GraalVM native image, so we enumerate
    // explicitly. Update this list when adding a new built-in image.
    private static final List<String> BUILTIN_FILES = List.of(
        "minimal.yaml"
        , "openjdk.yaml"
    );

    private String name;
    private String description = "";
    private String parent;
    private List<String> packages = List.of();

    public String getName()
    {
        return name;
    }

    public void setName(final String name)
    {
        this.name = name;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(final String description)
    {
        this.description = description;
    }

    public String getParent()
    {
        return parent;
    }

    public void setParent(final String parent)
    {
        this.parent = parent;
    }

    public List<String> getPackages()
    {
        return packages;
    }

    public void setPackages(final List<String> packages)
    {
        this.packages = packages;
    }

    /**
     * Whether this image is built from scratch (no parent).
     * Same semantics as incus-spawn's {@code ImageDef.isRoot()}.
     */
    public boolean isRoot()
    {
        return parent == null || parent.isBlank();
    }

    /**
     * Return the ordered list of ancestor images, starting from the
     * immediate parent and walking up to the root. Stops if a name
     * is missing from {@code defs}.
     */
    public static List<ImageDef> ancestors(
        final ImageDef start
        , final Map<String, ImageDef> defs
    )
    {
        final var result = new ArrayList<ImageDef>();
        var parentName = start.getParent();
        while (parentName != null && !parentName.isBlank())
        {
            final var p = defs.get(parentName);
            if (p == null)
            {
                break;
            }
            result.add(p);
            parentName = p.getParent();
        }
        return result;
    }

    /**
     * Load all built-in image definitions from the classpath.
     * Returns a map keyed by image name (e.g. "tpl-minimal").
     */
    public static Map<String, ImageDef> loadAll()
    {
        final var defs = new LinkedHashMap<String, ImageDef>();
        for (final var filename : BUILTIN_FILES)
        {
            final var def = loadResource(RESOURCE_DIR + filename);
            if (def != null)
            {
                defs.put(def.getName(), def);
            }
        }
        return defs;
    }

    /**
     * Find an image definition by name from the built-in set.
     */
    public static ImageDef findByName(
        final String name
        , final Map<String, ImageDef> defs
    )
    {
        return defs.get(name);
    }

    /**
     * Parse an image definition from a YAML string.
     */
    public static ImageDef parseYaml(final String yaml) throws IOException
    {
        return YAML.readValue(yaml, ImageDef.class);
    }

    private static ImageDef loadResource(final String resourcePath)
    {
        try (final InputStream is = ImageDef.class.getClassLoader()
            .getResourceAsStream(resourcePath))
        {
            if (is == null)
            {
                return null;
            }
            return YAML.readValue(is, ImageDef.class);
        }
        catch (final IOException e)
        {
            System.err.println("Failed to load image definition: "
                + resourcePath + ": " + e.getMessage());
            return null;
        }
    }
}
