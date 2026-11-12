package org.mendrugo.attimo.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Definition of a template image, loaded from YAML.
 *
 * <p>Mirrors incus-spawn's ImageDef: templates form a parent chain
 * where a root template (no parent) is the base, and children
 * add packages on top. Resolution order: built-in (classpath
 * {@code images/}) → search paths ({@code images/} subdirectory)
 * → project-local ({@code .attimo/images/}).
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

    private static final Path PROJECT_IMAGES_DIR = Path.of(".attimo/images");

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
        this.packages = packages == null ? List.of() : packages;
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
     * is missing from {@code defs} or if a cycle is detected.
     */
    public static List<ImageDef> ancestors(
        final ImageDef start
        , final Map<String, ImageDef> defs
    )
    {
        final var result = new ArrayList<ImageDef>();
        final var visited = new HashSet<String>();
        visited.add(start.getName());
        var parentName = start.getParent();
        while (parentName != null && !parentName.isBlank())
        {
            if (!visited.add(parentName))
            {
                break; // cycle detected
            }
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
     * Collect all packages for a template, walking the parent chain
     * from root to leaf. Packages from ancestors come first; duplicates
     * are removed preserving first occurrence order.
     *
     * <p>This is the API that cloud providers should call to get the
     * full package list for provisioning.
     *
     * @param imageDef the template to collect packages for
     * @param defs     all loaded image definitions
     * @return deduplicated package list, root-first order
     */
    public static List<String> collectAllPackages(
        final ImageDef imageDef
        , final Map<String, ImageDef> defs
    )
    {
        final var ancestors = ancestors(imageDef, defs);

        // Build chain root-first: ancestors are parent→root order,
        // so reverse them, then append the leaf template.
        final var chain = new ArrayList<ImageDef>();
        for (int i = ancestors.size() - 1; i >= 0; i--)
        {
            chain.add(ancestors.get(i));
        }
        chain.add(imageDef);

        // Collect packages preserving insertion order, dedup
        final var allPackages = new LinkedHashSet<String>();
        for (final var def : chain)
        {
            allPackages.addAll(def.getPackages());
        }
        return new ArrayList<>(allPackages);
    }

    /**
     * Resolve a template by name: load all definitions, find the template,
     * and collect all packages (including inherited from ancestors).
     *
     * <p>This is the single entry point both AWS and Blue Hat request
     * commands should call to validate and resolve a template before
     * any cloud resources are created.
     *
     * @param templateName the template name (e.g. "tpl-openjdk")
     * @return the resolved result, or null if the template is unknown
     */
    public static ResolvedTemplate resolve(final String templateName)
    {
        final var defs = loadAll();
        final var imageDef = findByName(templateName, defs);
        if (imageDef == null)
        {
            System.err.println("Error: unknown template '" + templateName + "'.");
            System.err.println("Available templates: "
                + String.join(", ", defs.keySet()));
            return null;
        }
        final var packages = collectAllPackages(imageDef, defs);
        return new ResolvedTemplate(imageDef, packages);
    }

    /**
     * A resolved template with its full package list (including inherited).
     */
    public record ResolvedTemplate(
        ImageDef imageDef
        , List<String> packages
    )
    {}

    /**
     * Load all image definitions: built-in first, then search paths,
     * then project-local. Later definitions with the same name override
     * earlier ones. Search paths come from {@link AttimoConfig}.
     *
     * <p>Same resolution order as incus-spawn:
     * built-in → search paths ({@code images/} subdirectory) → project-local.
     */
    public static Map<String, ImageDef> loadAll()
    {
        return loadAll(AttimoConfig.load().getSearchPaths());
    }

    /**
     * Load all image definitions with explicit search paths.
     */
    public static Map<String, ImageDef> loadAll(final List<String> searchPaths)
    {
        final var defs = new LinkedHashMap<String, ImageDef>();
        loadBuiltins(defs);
        for (final var searchPath : searchPaths)
        {
            final var expanded = expandTilde(searchPath);
            loadFromDirectory(Path.of(expanded).resolve("images"), defs);
        }
        loadFromDirectory(PROJECT_IMAGES_DIR, defs);
        return defs;
    }

    private static void loadBuiltins(final Map<String, ImageDef> defs)
    {
        for (final var filename : BUILTIN_FILES)
        {
            final var def = loadResource(RESOURCE_DIR + filename);
            if (def != null)
            {
                defs.put(def.getName(), def);
            }
        }
    }

    /**
     * Find an image definition by name.
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

    private static void loadFromDirectory(
        final Path dir
        , final Map<String, ImageDef> defs
    )
    {
        if (!Files.isDirectory(dir))
        {
            return;
        }

        try (final var stream = Files.list(dir))
        {
            stream
                .filter(p -> p.toString().endsWith(".yaml")
                    || p.toString().endsWith(".yml"))
                .sorted()
                .forEach(path ->
                {
                    try (final var is = Files.newInputStream(path))
                    {
                        final var def = YAML.readValue(is, ImageDef.class);
                        if (def.getName() != null)
                        {
                            defs.put(def.getName(), def);
                        }
                    }
                    catch (final IOException e)
                    {
                        System.err.println("Warning: failed to parse "
                            + path.getFileName() + ": " + e.getMessage());
                    }
                });
        }
        catch (final IOException e)
        {
            System.err.println("Warning: failed to scan " + dir
                + ": " + e.getMessage());
        }
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

    public static String expandTilde(final String path)
    {
        if (path.startsWith("~/"))
        {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
