package org.mendrugo.attimo.aws;

import java.util.List;

/**
 * Controls the size of the AWS instance launched for OpenJDK development.
 * Each tier targets a different build-time objective (excluding provisioning).
 *
 * <ul>
 *   <li><b>MICRO</b> — cheapest possible, 2–4 vCPUs, useful for smoke tests</li>
 *   <li><b>SMALL</b> — ~10 min OpenJDK build, 8–16 vCPUs</li>
 *   <li><b>MEDIUM</b> — ~5 min OpenJDK build, 16–32 vCPUs (default)</li>
 *   <li><b>LARGE</b> — ~2 min OpenJDK build, 32–64 vCPUs</li>
 * </ul>
 */
public enum InstanceSize
{
    MICRO(
        "micro"
        , "Cheapest (2-4 vCPUs)"
        , List.of("large", "xlarge")
    )
    , SMALL(
        "small"
        , "~10 min build (8-16 vCPUs)"
        , List.of("2xlarge", "4xlarge")
    )
    , MEDIUM(
        "medium"
        , "~5 min build (16-32 vCPUs)"
        , List.of("4xlarge", "8xlarge")
    )
    , LARGE(
        "large"
        , "~2 min build (32-64 vCPUs)"
        , List.of("8xlarge", "12xlarge", "16xlarge")
    );

    public static final InstanceSize DEFAULT = MEDIUM;

    private final String label;
    private final String description;
    private final List<String> awsSizes;

    InstanceSize(
        final String label
        , final String description
        , final List<String> awsSizes
    )
    {
        this.label = label;
        this.description = description;
        this.awsSizes = awsSizes;
    }

    public String label()
    {
        return label;
    }

    public String description()
    {
        return description;
    }

    public List<String> awsSizes()
    {
        return awsSizes;
    }

    /**
     * Parse a size label (case-insensitive) to an InstanceSize.
     *
     * @param label the size label (e.g. "medium", "LARGE")
     * @return the matching InstanceSize
     * @throws IllegalArgumentException if the label is unknown
     */
    public static InstanceSize fromLabel(final String label)
    {
        for (final InstanceSize size : values())
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
