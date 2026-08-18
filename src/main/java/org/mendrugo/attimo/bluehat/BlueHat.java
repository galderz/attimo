package org.mendrugo.attimo.bluehat;

/**
 * Blue Hat cloud provider constants.
 */
public final class BlueHat
{
    private BlueHat() {}

    /**
     * The cloud identifier used for config/state/SSH paths.
     * E.g. ~/.config/attimo/bh/config.yaml
     */
    public static final String CLOUD = "bh";

    /**
     * Default SSH user for Blue Hat VMs.
     */
    public static final String SSH_USER = "root";

    /**
     * Default OS for VM requests.
     */
    public static final String DEFAULT_OS = "RedHat 10.2";

    /**
     * Blue Hat proxy API port.
     */
    public static final int API_PORT = 8080;
}
