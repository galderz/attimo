package org.mendrugo.attimo.isa;

import java.util.List;

/**
 * A CPU ISA feature and the AWS instance families that support it.
 */
public record IsaFeature(
    String name
    , String description
    , String architecture
    , List<String> families
)
{}
