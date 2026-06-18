package org.mendrugo.attimo.ssh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps logical package names to OS-specific package names.
 * Handles differences between Fedora and Amazon Linux 2023.
 *
 * <p>Both use dnf, but package naming conventions differ:
 * <ul>
 *   <li>Fedora: {@code java-25-openjdk-devel}, {@code libcups-devel}</li>
 *   <li>AL2023: {@code java-21-amazon-corretto-devel}, {@code cups-devel}</li>
 * </ul>
 */
public final class OsPackages
{
    private OsPackages() {}

    /**
     * Supported OS types for package resolution.
     */
    public enum Os
    {
        FEDORA
        , AL2023
    }

    /**
     * AL2023 package name mappings. Keys are Fedora package names,
     * values are the AL2023 equivalents.
     */
    private static final Map<String, String> AL2023_MAPPINGS;

    /**
     * Packages not available on AL2023 (skipped during provisioning).
     */
    private static final Set<String> AL2023_UNAVAILABLE = Set.of(
        "java-25-openjdk-src"   // Corretto doesn't ship source
        , "capstone"            // not in AL2023 repos
        , "capstone-devel"
        , "capstone-tool"
    );

    static
    {
        final var m = new HashMap<String, String>();
        // JDK: AL2023 ships Amazon Corretto, not OpenJDK packages.
        m.put("java-25-openjdk-devel", "java-21-amazon-corretto-devel");
        m.put("java-25-openjdk-javadoc", "java-21-amazon-corretto-javadoc");
        // Library naming differences
        m.put("libcups-devel", "cups-devel");
        AL2023_MAPPINGS = Map.copyOf(m);
    }

    /**
     * JDK-dev packages in Fedora naming (the canonical list).
     */
    public static final List<String> JDK_DEV_PACKAGES = List.of(
        "gcc"
        , "gcc-c++"
        , "make"
        , "autoconf"
        , "java-25-openjdk-devel"
        , "java-25-openjdk-javadoc"
        , "java-25-openjdk-src"
        , "libcups-devel"
        , "libX11-devel"
        , "libXt-devel"
        , "libXrender-devel"
        , "libXrandr-devel"
        , "libXi-devel"
        , "libXtst-devel"
        , "alsa-lib-devel"
        , "fontconfig-devel"
        , "freetype-devel"
        , "capstone"
        , "capstone-devel"
        , "capstone-tool"
    );

    /**
     * Determine the OS type from the resolved AMI name.
     *
     * @param resolvedName the AMI name (e.g., "fedora-44", "al2023")
     * @return the OS type
     */
    public static Os detectOs(final String resolvedName)
    {
        if (resolvedName.startsWith("fedora-"))
        {
            return Os.FEDORA;
        }
        if ("al2023".equals(resolvedName))
        {
            return Os.AL2023;
        }

        // Default to Fedora naming if unknown
        return Os.FEDORA;
    }

    /**
     * Resolve a list of canonical (Fedora) package names to OS-specific names.
     *
     * @param packages the canonical package names
     * @param os       the target OS
     * @return result containing installable packages and skipped packages
     */
    public static ResolvedPackages resolve(
        final List<String> packages
        , final Os os
    )
    {
        if (os == Os.FEDORA)
        {
            return new ResolvedPackages(packages, List.of());
        }

        final var installable = new ArrayList<String>();
        final var skipped = new ArrayList<String>();

        for (final var pkg : packages)
        {
            if (AL2023_UNAVAILABLE.contains(pkg))
            {
                skipped.add(pkg);
            }
            else if (AL2023_MAPPINGS.containsKey(pkg))
            {
                installable.add(AL2023_MAPPINGS.get(pkg));
            }
            else
            {
                // Package name is the same on AL2023
                installable.add(pkg);
            }
        }

        return new ResolvedPackages(installable, skipped);
    }

    /**
     * Result of package name resolution.
     *
     * @param installable packages that can be installed
     * @param skipped     packages not available on this OS
     */
    public record ResolvedPackages(
        List<String> installable
        , List<String> skipped
    ) {}
}
