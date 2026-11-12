package org.mendrugo.attimo.bluehat;

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
