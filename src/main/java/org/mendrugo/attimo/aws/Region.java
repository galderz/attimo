package org.mendrugo.attimo.aws;

/**
 * AWS regions known to attimo. Each region has a code (used by the AWS SDK)
 * and a human-readable description for display.
 */
public enum Region
{
    // EMEA
    EU_WEST_1("eu-west-1", "Ireland")
    , EU_WEST_2("eu-west-2", "London")
    , EU_WEST_3("eu-west-3", "Paris")
    , EU_CENTRAL_1("eu-central-1", "Frankfurt")
    , EU_CENTRAL_2("eu-central-2", "Zurich")
    , EU_NORTH_1("eu-north-1", "Stockholm")
    , EU_SOUTH_1("eu-south-1", "Milan")
    , EU_SOUTH_2("eu-south-2", "Spain")
    , ME_SOUTH_1("me-south-1", "Bahrain")
    , ME_CENTRAL_1("me-central-1", "UAE")
    , AF_SOUTH_1("af-south-1", "Cape Town")

    // Americas
    , US_EAST_1("us-east-1", "N. Virginia")
    , US_EAST_2("us-east-2", "Ohio")
    , US_WEST_1("us-west-1", "N. California")
    , US_WEST_2("us-west-2", "Oregon")
    , CA_CENTRAL_1("ca-central-1", "Canada (Central)")
    , CA_WEST_1("ca-west-1", "Canada (Calgary)")
    , SA_EAST_1("sa-east-1", "São Paulo")

    // Asia-Pacific
    , AP_NORTHEAST_1("ap-northeast-1", "Tokyo")
    , AP_NORTHEAST_2("ap-northeast-2", "Seoul")
    , AP_NORTHEAST_3("ap-northeast-3", "Osaka")
    , AP_SOUTHEAST_1("ap-southeast-1", "Singapore")
    , AP_SOUTHEAST_2("ap-southeast-2", "Sydney")
    , AP_SOUTHEAST_3("ap-southeast-3", "Jakarta")
    , AP_SOUTHEAST_4("ap-southeast-4", "Melbourne")
    , AP_SOUTHEAST_5("ap-southeast-5", "Malaysia")
    , AP_SOUTH_1("ap-south-1", "Mumbai")
    , AP_SOUTH_2("ap-south-2", "Hyderabad");

    private final String code;
    private final String description;

    Region(final String code, final String description)
    {
        this.code = code;
        this.description = description;
    }

    /**
     * AWS region code (e.g., "eu-west-1"). Used with the AWS SDK.
     */
    public String code()
    {
        return code;
    }

    /**
     * Human-readable location (e.g., "Ireland"). Used for display.
     */
    public String description()
    {
        return description;
    }

    /**
     * Look up a Region by its AWS code.
     *
     * @param code AWS region code (e.g., "eu-west-1")
     * @return the matching Region
     * @throws IllegalArgumentException if the code is not a known region
     */
    public static Region fromCode(final String code)
    {
        for (final Region region : values())
        {
            if (region.code.equals(code))
            {
                return region;
            }
        }

        throw new IllegalArgumentException("Unknown AWS region: " + code);
    }

    /**
     * Check if an AWS region code is known.
     */
    public static boolean isKnown(final String code)
    {
        for (final Region region : values())
        {
            if (region.code.equals(code))
            {
                return true;
            }
        }

        return false;
    }
}
