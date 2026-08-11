package org.mendrugo.attimo;

import java.nio.file.Path;

/**
 * Resolves filesystem paths following XDG conventions.
 * Configuration at ~/.config/attimo/{cloud}/, cache at ~/.cache/attimo/{cloud}/.
 * Each cloud provider gets its own subdirectory for config, state, and SSH keys.
 */
public final class Environment
{
    private Environment() {}

    public static Path home()
    {
        return Path.of(System.getProperty("user.home"));
    }

    /**
     * Root config dir: ~/.config/attimo/
     */
    public static Path configRoot()
    {
        final var xdgConfig = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfig != null && !xdgConfig.isBlank())
        {
            return Path.of(xdgConfig, "attimo");
        }

        return home().resolve(".config").resolve("attimo");
    }

    /**
     * Cloud-specific config dir: ~/.config/attimo/{cloud}/
     */
    public static Path configDir(final String cloud)
    {
        return configRoot().resolve(cloud);
    }

    /**
     * Root cache dir: ~/.cache/attimo/
     */
    public static Path cacheRoot()
    {
        final var xdgCache = System.getenv("XDG_CACHE_HOME");
        if (xdgCache != null && !xdgCache.isBlank())
        {
            return Path.of(xdgCache, "attimo");
        }

        return home().resolve(".cache").resolve("attimo");
    }

    /**
     * Cloud-specific cache dir: ~/.cache/attimo/{cloud}/
     */
    public static Path cacheDir(final String cloud)
    {
        return cacheRoot().resolve(cloud);
    }

    public static Path configFile(final String cloud)
    {
        return configDir(cloud).resolve("config.yaml");
    }

    public static Path stateFile(final String cloud)
    {
        return configDir(cloud).resolve("state.yaml");
    }

    public static Path sshDir(final String cloud)
    {
        return configDir(cloud).resolve("ssh");
    }

    public static Path sshKeyFile(final String cloud)
    {
        return sshDir(cloud).resolve("id_ed25519");
    }

    public static Path sshPubKeyFile(final String cloud)
    {
        return sshDir(cloud).resolve("id_ed25519.pub");
    }
}
