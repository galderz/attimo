package org.mendrugo.attimo.config;

import java.util.ArrayList;
import java.util.List;

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
            "eu-west-1"
            , "eu-west-2"
            , "eu-west-3"
            , "eu-central-1"
            , "eu-central-2"
            , "eu-north-1"
            , "eu-south-1"
            , "eu-south-2"
            , "me-south-1"
            , "me-central-1"
            , "af-south-1"
        )
        , List.of("eu-west-1", "eu-central-1", "me-south-1")
        , "EMEA (Europe, Middle East & Africa)"
    )
    , AMERICAS(
        List.of(
            "us-east-1"
            , "us-east-2"
            , "us-west-1"
            , "us-west-2"
            , "ca-central-1"
            , "ca-west-1"
            , "sa-east-1"
        )
        , List.of("us-east-1", "us-west-2", "ca-central-1")
        , "Americas"
    )
    , ASIA_PACIFIC(
        List.of(
            "ap-northeast-1"
            , "ap-northeast-2"
            , "ap-northeast-3"
            , "ap-southeast-1"
            , "ap-southeast-2"
            , "ap-southeast-3"
            , "ap-southeast-4"
            , "ap-southeast-5"
            , "ap-south-1"
            , "ap-south-2"
        )
        , List.of("ap-northeast-1", "ap-southeast-1", "ap-south-1")
        , "Asia-Pacific"
    );

    private final List<String> regions;
    private final List<String> representatives;
    private final String displayName;

    Continent(
        final List<String> regions
        , final List<String> representatives
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
    public List<String> regions()
    {
        return regions;
    }

    /**
     * Three high-volume representative regions used when this continent
     * is a fallback (to keep query count bounded).
     */
    public List<String> representatives()
    {
        return representatives;
    }

    /**
     * Human-readable name for display in the CLI.
     */
    public String displayName()
    {
        return displayName;
    }

    /**
     * Find the continent containing the given region.
     *
     * @param region AWS region code (e.g., "eu-west-1")
     * @return the continent containing this region
     * @throws IllegalArgumentException if the region is not in any known continent
     */
    public static Continent forRegion(final String region)
    {
        for (final Continent continent : values())
        {
            if (continent.regions.contains(region))
            {
                return continent;
            }
        }

        throw new IllegalArgumentException(
            "Unknown AWS region: " + region
            + ". Known regions: " + allRegions()
        );
    }

    /**
     * Check if a region is known to any continent.
     */
    public static boolean isKnown(final String region)
    {
        for (final Continent continent : values())
        {
            if (continent.regions.contains(region))
            {
                return true;
            }
        }

        return false;
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

    /**
     * Region display info for init command: region code + description.
     */
    public static String regionDescription(final String region)
    {
        return switch (region)
        {
            case "eu-west-1" -> "Ireland";
            case "eu-west-2" -> "London";
            case "eu-west-3" -> "Paris";
            case "eu-central-1" -> "Frankfurt";
            case "eu-central-2" -> "Zurich";
            case "eu-north-1" -> "Stockholm";
            case "eu-south-1" -> "Milan";
            case "eu-south-2" -> "Spain";
            case "me-south-1" -> "Bahrain";
            case "me-central-1" -> "UAE";
            case "af-south-1" -> "Cape Town";
            case "us-east-1" -> "N. Virginia";
            case "us-east-2" -> "Ohio";
            case "us-west-1" -> "N. California";
            case "us-west-2" -> "Oregon";
            case "ca-central-1" -> "Canada (Central)";
            case "ca-west-1" -> "Canada (Calgary)";
            case "sa-east-1" -> "São Paulo";
            case "ap-northeast-1" -> "Tokyo";
            case "ap-northeast-2" -> "Seoul";
            case "ap-northeast-3" -> "Osaka";
            case "ap-southeast-1" -> "Singapore";
            case "ap-southeast-2" -> "Sydney";
            case "ap-southeast-3" -> "Jakarta";
            case "ap-southeast-4" -> "Melbourne";
            case "ap-southeast-5" -> "Malaysia";
            case "ap-south-1" -> "Mumbai";
            case "ap-south-2" -> "Hyderabad";
            default -> "";
        };
    }

    private static List<String> allRegions()
    {
        final var all = new ArrayList<String>();
        for (final Continent continent : values())
        {
            all.addAll(continent.regions);
        }

        return all;
    }
}
