package org.mendrugo.attimo.aws;

import java.util.ArrayList;
import java.util.List;

import static org.mendrugo.attimo.aws.Region.*;

/**
 * Geographic continent groupings of AWS regions for spot pricing
 * comparison and capacity fallback. Three continents cover all
 * AWS regions, with EMEA including Middle East and Africa.
 *
 * <p>When requesting a spot instance, all regions in the user's
 * home continent are queried. Representative regions in the other
 * two continents are also queried as fallback candidates, with
 * graduated pricing penalties based on distance.
 */
public enum Continent
{
    EMEA(
        List.of(
            EU_WEST_1
            , EU_WEST_2
            , EU_WEST_3
            , EU_CENTRAL_1
            , EU_CENTRAL_2
            , EU_NORTH_1
            , EU_SOUTH_1
            , EU_SOUTH_2
            , ME_SOUTH_1
            , ME_CENTRAL_1
            , AF_SOUTH_1
        )
        , List.of(EU_WEST_1, EU_CENTRAL_1, ME_SOUTH_1)
        , "EMEA (Europe, Middle East & Africa)"
    )
    , AMERICAS(
        List.of(
            US_EAST_1
            , US_EAST_2
            , US_WEST_1
            , US_WEST_2
            , CA_CENTRAL_1
            , CA_WEST_1
            , SA_EAST_1
        )
        , List.of(US_EAST_1, US_WEST_2, CA_CENTRAL_1)
        , "Americas"
    )
    , ASIA_PACIFIC(
        List.of(
            AP_NORTHEAST_1
            , AP_NORTHEAST_2
            , AP_NORTHEAST_3
            , AP_SOUTHEAST_1
            , AP_SOUTHEAST_2
            , AP_SOUTHEAST_3
            , AP_SOUTHEAST_4
            , AP_SOUTHEAST_5
            , AP_SOUTH_1
            , AP_SOUTH_2
        )
        , List.of(AP_NORTHEAST_1, AP_SOUTHEAST_1, AP_SOUTH_1)
        , "Asia-Pacific"
    );

    private final List<Region> regions;
    private final List<Region> representatives;
    private final String displayName;

    Continent(
        final List<Region> regions
        , final List<Region> representatives
        , final String displayName
    )
    {
        this.regions = regions;
        this.representatives = representatives;
        this.displayName = displayName;
    }

    /**
     * All regions in this continent.
     */
    public List<Region> regions()
    {
        return regions;
    }

    /**
     * All region codes in this continent (convenience for AWS SDK calls).
     */
    public List<String> regionCodes()
    {
        return regions.stream().map(Region::code).toList();
    }

    /**
     * Three high-volume representative regions used when this continent
     * is a fallback (to keep query count bounded).
     */
    public List<Region> representatives()
    {
        return representatives;
    }

    /**
     * Representative region codes (convenience for AWS SDK calls).
     */
    public List<String> representativeCodes()
    {
        return representatives.stream().map(Region::code).toList();
    }

    /**
     * Human-readable name for display in the CLI.
     */
    public String displayName()
    {
        return displayName;
    }

    /**
     * Find the continent containing the given region code.
     *
     * @param regionCode AWS region code (e.g., "eu-west-1")
     * @return the continent containing this region
     * @throws IllegalArgumentException if the region is not in any known continent
     */
    public static Continent forRegion(final String regionCode)
    {
        for (final Continent continent : values())
        {
            for (final Region r : continent.regions)
            {
                if (r.code().equals(regionCode))
                {
                    return continent;
                }
            }
        }

        throw new IllegalArgumentException(
            "Unknown AWS region: " + regionCode
        );
    }

    /**
     * Return the other two continents (not this one),
     * in declaration order.
     */
    public List<Continent> others()
    {
        final var result = new ArrayList<Continent>();
        for (final Continent c : values())
        {
            if (c != this)
            {
                result.add(c);
            }
        }

        return result;
    }
}
