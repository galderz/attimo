package org.mendrugo.attimo.aws;

public record SpotRecommendation(
    String instanceType
    , String region
    , String availabilityZone
    , double pricePerHour
    , String rationale
)
{}
