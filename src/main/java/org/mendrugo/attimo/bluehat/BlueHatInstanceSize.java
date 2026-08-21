package org.mendrugo.attimo.bluehat;

/**
 * Controls the size of the Blue Hat VM launched for OpenJDK development.
 * Maps size tiers to CPU and memory (GB) values.
 *
 * <p>Memory-to-CPU ratio is 2:1 for development workloads,
 * providing enough RAM for OpenJDK builds and jtreg test runs.
 *
 * <ul>
 *   <li><b>MICRO</b> — 1 CPU, 2 GB — smoke tests and verification</li>
 *   <li><b>SMALL</b> — 8 CPUs, 16 GB — full builds (~10 min)</li>
 *   <li><b>MEDIUM</b> — 16 CPUs, 32 GB — iterative development (~5 min, default)</li>
 *   <li><b>LARGE</b> — 32 CPUs, 64 GB — fast builds and jtreg runs (~2 min)</li>
 * </ul>
 */
public enum BlueHatInstanceSize
{
    MICRO("micro", "Cheapest (1 CPU, 2 GB)", 1, 2)
    , SMALL("small", "~10 min build (8 CPUs, 16 GB)", 8, 16)
    , MEDIUM("medium", "~5 min build (16 CPUs, 32 GB)", 16, 32)
    , LARGE("large", "~2 min build (32 CPUs, 64 GB)", 32, 64);

    public static final BlueHatInstanceSize DEFAULT = MEDIUM;

    private final String label;
    private final String description;
    private final int cpus;
    private final int memoryGb;

    BlueHatInstanceSize(
        final String label
        , final String description
        , final int cpus
        , final int memoryGb
    )
    {
        this.label = label;
        this.description = description;
        this.cpus = cpus;
        this.memoryGb = memoryGb;
    }

    public String label()
    {
        return label;
    }

    public String description()
    {
        return description;
    }

    public int cpus()
    {
        return cpus;
    }

    public int memoryGb()
    {
        return memoryGb;
    }

    /**
     * Parse a size label (case-insensitive) to a BlueHatInstanceSize.
     *
     * @param label the size label (e.g. "medium", "LARGE")
     * @return the matching BlueHatInstanceSize
     * @throws IllegalArgumentException if the label is unknown
     */
    public static BlueHatInstanceSize fromLabel(final String label)
    {
        for (final BlueHatInstanceSize size : values())
        {
            if (size.label.equalsIgnoreCase(label))
            {
                return size;
            }
        }

        throw new IllegalArgumentException(
            "Unknown instance size: '" + label + "'. "
                + "Valid sizes: micro, small, medium, large"
        );
    }
}
