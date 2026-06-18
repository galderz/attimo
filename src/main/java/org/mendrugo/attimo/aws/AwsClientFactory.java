package org.mendrugo.attimo.aws;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.net.URI;

/**
 * Creates AWS SDK v2 clients using the default credential chain.
 * Attimo never stores AWS credentials — authentication is delegated
 * entirely to the SDK (env vars, ~/.aws/credentials, SSO, etc.).
 */
public class AwsClientFactory
{
    private final URI endpointOverride;

    public AwsClientFactory()
    {
        this(null);
    }

    /**
     * Constructor for testing with LocalStack or other endpoints.
     *
     * @param endpointOverride custom endpoint URI, or null for real AWS
     */
    public AwsClientFactory(final URI endpointOverride)
    {
        this.endpointOverride = endpointOverride;
    }

    public Ec2Client ec2(final String region)
    {
        final var builder = Ec2Client.builder()
            .region(Region.of(region))
            .httpClient(UrlConnectionHttpClient.builder().build());

        if (endpointOverride != null)
        {
            builder.endpointOverride(endpointOverride);
        }

        return builder.build();
    }

    public SsmClient ssm(final String region)
    {
        final var builder = SsmClient.builder()
            .region(Region.of(region))
            .httpClient(UrlConnectionHttpClient.builder().build());

        if (endpointOverride != null)
        {
            builder.endpointOverride(endpointOverride);
        }

        return builder.build();
    }

    /**
     * Validate that AWS credentials are available and working.
     *
     * @return null on success, or an error message describing the problem
     */
    public String validateCredentials()
    {
        try
        {
            final var builder = StsClient.builder()
                .httpClient(UrlConnectionHttpClient.builder().build());
            if (endpointOverride != null)
            {
                builder.endpointOverride(endpointOverride);
            }

            try (final StsClient sts = builder.build())
            {
                final GetCallerIdentityResponse identity = sts.getCallerIdentity();
                System.out.println("  Authenticated as: " + identity.arn());
                return null;
            }
        }
        catch (final Exception e)
        {
            return "AWS authentication failed: " + e.getMessage();
        }
    }
}
