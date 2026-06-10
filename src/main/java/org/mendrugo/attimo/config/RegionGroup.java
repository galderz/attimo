package org.mendrugo.attimo.config;

import java.util.List;

/**
 * Geographic groupings of AWS regions for spot pricing comparison
 * and capacity fallback. The user's preferred region determines
 * the group; all regions in the group are searched for spot availability.
 */
public enum RegionGroup
{
    EUROPE(
        "eu-west-1"
        , "eu-west-2"
        , "eu-west-3"
        , "eu-central-1"
        , "eu-central-2"
        , "eu-north-1"
        , "eu-south-1"
        , "eu-south-2"
    )
    , US_EAST(
        "us-east-1"
        , "us-east-2"
    )
    , US_WEST(
        "us-west-1"
        , "us-west-2"
    )
    , AP_SOUTHEAST(
        "ap-southeast-1"
        , "ap-southeast-2"
        , "ap-southeast-3"
        , "ap-southeast-4"
        , "ap-southeast-5"
    )
    , AP_NORTHEAST(
        "ap-northeast-1"
        , "ap-northeast-2"
        , "ap-northeast-3"
    )
    , AP_SOUTH(
        "ap-south-1"
        , "ap-south-2"
    )
    , SOUTH_AMERICA("sa-east-1")
    , MIDDLE_EAST(
        "me-south-1"
        , "me-central-1"
    )
    , AFRICA("af-south-1")
    , CANADA(
        "ca-central-1"
        , "ca-west-1"
    );

    private final List<String> regions;

    RegionGroup(final String... regions)
    {
        this.regions = List.of(regions);
    }

    public List<String> regions()
    {
        return regions;
    }

    /**
     * Find the region group containing the given region.
     *
     * @param region AWS region code (e.g., "eu-west-1")
     * @return the group containing this region
     * @throws IllegalArgumentException if the region is not in any known group
     */
    public static RegionGroup forRegion(final String region)
    {
        for (final RegionGroup group : values())
        {
            if (group.regions.contains(region))
            {
                return group;
            }
        }

        throw new IllegalArgumentException(
            "Unknown AWS region: " + region
            + ". Known regions: " + allRegions()
        );
    }

    /**
     * Check if a region is known to any group.
     */
    public static boolean isKnown(final String region)
    {
        for (final RegionGroup group : values())
        {
            if (group.regions.contains(region))
            {
                return true;
            }
        }

        return false;
    }

    private static List<String> allRegions()
    {
        final var all = new java.util.ArrayList<String>();
        for (final RegionGroup group : values())
        {
            all.addAll(group.regions);
        }

        return all;
    }
}
