package org.mendrugo.attimo.bluehat;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Reads Blue Hat configuration from Quarkus/MicroProfile Config.
 *
 * <ul>
 *   <li>{@code attimo.bluehat.host-name} — the Blue Hat cloud host (default: "localhost")</li>
 *   <li>{@code attimo.bluehat.repository} — git repository to clone when running locally</li>
 *   <li>{@code attimo.bluehat.local-port} — port for the local Quarkus app (default: 18080)</li>
 * </ul>
 *
 * When host-name is "localhost", attimo manages a local Blue Hat cloud instance.
 * Otherwise, it connects to a remote Blue Hat cloud at the specified host on port 8080.
 */
public final class BlueHatSettings
{
    private BlueHatSettings() {}

    public static String hostName()
    {
        return ConfigProvider.getConfig()
            .getOptionalValue("attimo.bluehat.host-name", String.class)
            .orElse("localhost");
    }

    public static String repository()
    {
        return ConfigProvider.getConfig()
            .getOptionalValue("attimo.bluehat.repository", String.class)
            .orElse("https://github.com/attimo/bluehat.git");
    }

    public static int localPort()
    {
        return ConfigProvider.getConfig()
            .getOptionalValue("attimo.bluehat.local-port", Integer.class)
            .orElse(18080);
    }

    /**
     * Whether the Blue Hat cloud runs locally (host-name is "localhost").
     */
    public static boolean isLocal()
    {
        return "localhost".equals(hostName());
    }

    /**
     * Returns the API port to use — local port when running locally,
     * standard API port (8080) for remote hosts.
     */
    public static int apiPort()
    {
        return isLocal() ? localPort() : BlueHat.API_PORT;
    }
}
