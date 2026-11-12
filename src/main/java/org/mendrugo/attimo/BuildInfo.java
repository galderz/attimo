package org.mendrugo.attimo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildInfo {
    private static BuildInfo INSTANCE;

    private final String version;

    private BuildInfo() {
        final Properties props = new Properties();
        try (InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream("build.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
        }

        this.version = props.getProperty("version", "dev");
    }

    public static synchronized BuildInfo instance() {
        if (INSTANCE == null) {
            INSTANCE = new BuildInfo();
        }

        return INSTANCE;
    }

    public String version() {
        return version;
    }

    public String runtime() {
        return System.getProperty("java.vm.name", "Unknown") + " " + System.getProperty("java.vm.version", "");
    }
}
