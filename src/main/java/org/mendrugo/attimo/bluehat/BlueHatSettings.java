package org.mendrugo.attimo.bluehat;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Reads Blue Hat configuration from Quarkus/MicroProfile Config.
 *
 * <ul>
 *   <li>{@code attimo.bluehat.host-name} — the Blue Hat cloud host (default: "localhost")</li>
 *   <li>{@code attimo.bluehat.repository} — git repository to clone when running locally</li>
 * </ul>
 *
 * When host-name is "localhost", attimo manages a local Blue Hat cloud instance
 * bound to localhost on the default Quarkus port (8080).
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

    /**
     * Whether the Blue Hat cloud runs locally (host-name is "localhost").
     */
    public static boolean isLocal()
    {
        return "localhost".equals(hostName());
    }
}
