package org.mendrugo.attimo;

import java.nio.file.Path;

/**
 * Resolves filesystem paths following XDG conventions.
 * Configuration at ~/.config/attimo/, cache at ~/.cache/attimo/.
 */
public final class Environment
{
    private Environment() {}

    public static Path home()
    {
        return Path.of(System.getProperty("user.home"));
    }

    public static Path configDir()
    {
        final var xdgConfig = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfig != null && !xdgConfig.isBlank())
        {
            return Path.of(xdgConfig, "attimo");
        }

        return home().resolve(".config").resolve("attimo");
    }

    public static Path cacheDir()
    {
        final var xdgCache = System.getenv("XDG_CACHE_HOME");
        if (xdgCache != null && !xdgCache.isBlank())
        {
            return Path.of(xdgCache, "attimo");
        }

        return home().resolve(".cache").resolve("attimo");
    }

    public static Path configFile()
    {
        return configDir().resolve("config.yaml");
    }

    public static Path stateFile()
    {
        return configDir().resolve("state.yaml");
    }

    public static Path sshDir()
    {
        return configDir().resolve("ssh");
    }

    public static Path sshKeyFile()
    {
        return sshDir().resolve("id_ed25519");
    }

    public static Path sshPubKeyFile()
    {
        return sshDir().resolve("id_ed25519.pub");
    }
}
