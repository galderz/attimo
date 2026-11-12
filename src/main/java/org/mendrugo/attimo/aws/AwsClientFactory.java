package org.mendrugo.attimo.aws;

import java.net.URI;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

public class AwsClientFactory {
    private final URI endpointOverride;

    public AwsClientFactory() {
        this(null);
    }

    public AwsClientFactory(URI endpointOverride) {
        this.endpointOverride = endpointOverride;
    }

    public Ec2Client ec2(String region) {
        final Ec2ClientBuilder builder = Ec2Client.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.builder().build());

        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride);
        }

        return builder.build();
    }

    public SsmClient ssm(String region) {
        final SsmClientBuilder builder = SsmClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.builder().build());

        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride);
        }

        return builder.build();
    }

    public String validateCredentials() {
        try {
            final StsClientBuilder builder = StsClient.builder().httpClient(UrlConnectionHttpClient.builder()
                    .build());
            if (endpointOverride != null) {
                builder.endpointOverride(endpointOverride);
            }

            try (StsClient sts = builder.build()) {
                final GetCallerIdentityResponse identity = sts.getCallerIdentity();
                System.out.println("  Authenticated as: " + identity.arn());
                return null;
            }
        } catch (Exception e) {
            return "AWS authentication failed: " + e.getMessage();
        }
    }
}
