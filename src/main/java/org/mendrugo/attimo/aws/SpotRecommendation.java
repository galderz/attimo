package org.mendrugo.attimo.aws;

/**
 * The result of spot instance selection: the best instance type, region,
 * and price, with a human-readable explanation of why it was chosen.
 */
public record SpotRecommendation(
    String instanceType
    , String region
    , String availabilityZone
    , double pricePerHour
    , String rationale
)
{}
