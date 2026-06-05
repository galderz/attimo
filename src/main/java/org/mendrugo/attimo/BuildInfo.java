package org.mendrugo.attimo;

import java.io.IOException;
import java.util.Properties;

public final class BuildInfo
{
    private static BuildInfo INSTANCE;

    private final String version;

    private BuildInfo()
    {
        final Properties props = new Properties();
        try (final var is = getClass().getClassLoader().getResourceAsStream("build.properties"))
        {
            if (is != null)
            {
                props.load(is);
            }
        }
        catch (final IOException e)
        {
            // ignore — defaults will be used
        }

        this.version = props.getProperty("version", "dev");
    }

    public static synchronized BuildInfo instance()
    {
        if (INSTANCE == null)
        {
            INSTANCE = new BuildInfo();
        }

        return INSTANCE;
    }

    public String version()
    {
        return version;
    }

    public String runtime()
    {
        return System.getProperty("java.vm.name", "Unknown")
            + " " + System.getProperty("java.vm.version", "");
    }
}
