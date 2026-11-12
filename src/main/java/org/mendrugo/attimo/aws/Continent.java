package org.mendrugo.attimo.aws;

import java.util.ArrayList;
import java.util.List;

import static org.mendrugo.attimo.aws.Region.AF_SOUTH_1;
import static org.mendrugo.attimo.aws.Region.AP_NORTHEAST_1;
import static org.mendrugo.attimo.aws.Region.AP_NORTHEAST_2;
import static org.mendrugo.attimo.aws.Region.AP_NORTHEAST_3;
import static org.mendrugo.attimo.aws.Region.AP_SOUTHEAST_1;
import static org.mendrugo.attimo.aws.Region.AP_SOUTHEAST_2;
import static org.mendrugo.attimo.aws.Region.AP_SOUTHEAST_3;
import static org.mendrugo.attimo.aws.Region.AP_SOUTHEAST_4;
import static org.mendrugo.attimo.aws.Region.AP_SOUTHEAST_5;
import static org.mendrugo.attimo.aws.Region.AP_SOUTH_1;
import static org.mendrugo.attimo.aws.Region.AP_SOUTH_2;
import static org.mendrugo.attimo.aws.Region.CA_CENTRAL_1;
import static org.mendrugo.attimo.aws.Region.CA_WEST_1;
import static org.mendrugo.attimo.aws.Region.EU_CENTRAL_1;
import static org.mendrugo.attimo.aws.Region.EU_CENTRAL_2;
import static org.mendrugo.attimo.aws.Region.EU_NORTH_1;
import static org.mendrugo.attimo.aws.Region.EU_SOUTH_1;
import static org.mendrugo.attimo.aws.Region.EU_SOUTH_2;
import static org.mendrugo.attimo.aws.Region.EU_WEST_1;
import static org.mendrugo.attimo.aws.Region.EU_WEST_2;
import static org.mendrugo.attimo.aws.Region.EU_WEST_3;
import static org.mendrugo.attimo.aws.Region.ME_CENTRAL_1;
import static org.mendrugo.attimo.aws.Region.ME_SOUTH_1;
import static org.mendrugo.attimo.aws.Region.SA_EAST_1;
import static org.mendrugo.attimo.aws.Region.US_EAST_1;
import static org.mendrugo.attimo.aws.Region.US_EAST_2;
import static org.mendrugo.attimo.aws.Region.US_WEST_1;
import static org.mendrugo.attimo.aws.Region.US_WEST_2;
import static org.mendrugo.attimo.aws.Region.values;

public enum Continent {
    EMEA(
            List.of(EU_WEST_1, EU_WEST_2, EU_WEST_3, EU_CENTRAL_1, EU_CENTRAL_2, EU_NORTH_1,
                    EU_SOUTH_1, EU_SOUTH_2, ME_SOUTH_1, ME_CENTRAL_1, AF_SOUTH_1),
            List.of(EU_WEST_1, EU_CENTRAL_1, ME_SOUTH_1),
            "EMEA (Europe, Middle East & Africa)"),
    AMERICAS(
            List.of(US_EAST_1, US_EAST_2, US_WEST_1, US_WEST_2, CA_CENTRAL_1, CA_WEST_1,
                    SA_EAST_1),
            List.of(US_EAST_1, US_WEST_2, CA_CENTRAL_1),
            "Americas"),
    ASIA_PACIFIC(
            List.of(AP_NORTHEAST_1, AP_NORTHEAST_2, AP_NORTHEAST_3, AP_SOUTHEAST_1, AP_SOUTHEAST_2, AP_SOUTHEAST_3,
                    AP_SOUTHEAST_4, AP_SOUTHEAST_5, AP_SOUTH_1, AP_SOUTH_2),
            List.of(AP_NORTHEAST_1, AP_SOUTHEAST_1, AP_SOUTH_1),
            "Asia-Pacific");

    private final List<Region> regions;
    private final List<Region> representatives;
    private final String displayName;

    Continent(List<Region> regions, List<Region> representatives, String displayName) {
        this.regions = regions;
        this.representatives = representatives;
        this.displayName = displayName;
    }

    public List<Region> regions() {
        return regions;
    }

    public List<String> regionCodes() {
        return regions.stream()
                      .map(Region::code)
                      .toList();
    }

    public List<Region> representatives() {
        return representatives;
    }

    public List<String> representativeCodes() {
        return representatives.stream()
                              .map(Region::code)
                              .toList();
    }

    public String displayName() {
        return displayName;
    }

    public static Continent forRegion(String regionCode) {
        for (Continent continent : values()) {
            for (Region r : continent.regions) {
                if (r.code().equals(regionCode)) {
                    return continent;
                }
            }
        }

        throw new IllegalArgumentException("Unknown AWS region: " + regionCode);
    }

    public List<Continent> others() {
        final ArrayList<Continent> result = new ArrayList<Continent>();
        for (Continent c : values()) {
            if (c != this) {
                result.add(c);
            }
        }

        return result;
    }
}
