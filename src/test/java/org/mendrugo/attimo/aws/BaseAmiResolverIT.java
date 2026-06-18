package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ssm.SsmClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.EC2;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SSM;

/**
 * Integration tests for BaseAmiResolver against LocalStack.
 * LocalStack does not have real Fedora AMIs, so these tests verify
 * the search behaviour (pattern matching, error handling) rather than
 * finding actual images.
 */
@Testcontainers
class BaseAmiResolverIT
{
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:4.4")
    )
        .withServices(EC2, SSM);

    @Test
    void describeImagesWorksAgainstLocalStack()
    {
        // Verify we can call DescribeImages without error
        try (final var ec2 = ec2Client())
        {
            final var response = ec2.describeImages(
                DescribeImagesRequest.builder()
                    .filters(
                        Filter.builder()
                            .name("name")
                            .values("nonexistent-*")
                            .build()
                    )
                    .build()
            );

            // Should return empty, not throw
            assertThat(response.images()).isEmpty();
        }
    }

    @Test
    void resolverSearchesFedoraAndFallsBack()
    {
        // LocalStack has no real AMIs, so the resolver should either
        // throw (no AMIs at all) or return a fallback result.
        // We verify it doesn't crash and exercises the search logic.
        try (final var ec2 = ec2Client())
        {
            final var resolver = new BaseAmiResolver();

            try
            {
                final var result = resolver.resolveWithFallback(
                    "fedora-44", ec2, "x86_64", ssmClient()
                );
                // If LocalStack returns any AMI, verify the result is valid
                assertThat(result.amiId()).isNotBlank();
                assertThat(result.sshUser()).isNotBlank();
            }
            catch (final AwsException e)
            {
                // Expected — no AMIs in LocalStack
                assertThat(e.getMessage()).contains("No suitable AMI found");
            }
        }
    }

    private Ec2Client ec2Client()
    {
        return Ec2Client.builder()
            .endpointOverride(LOCALSTACK.getEndpointOverride(EC2))
            .credentialsProvider(credentials())
            .region(Region.of(LOCALSTACK.getRegion()))
            .build();
    }

    private SsmClient ssmClient()
    {
        return SsmClient.builder()
            .endpointOverride(LOCALSTACK.getEndpointOverride(SSM))
            .credentialsProvider(credentials())
            .region(Region.of(LOCALSTACK.getRegion()))
            .build();
    }

    private StaticCredentialsProvider credentials()
    {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(
                LOCALSTACK.getAccessKey()
                , LOCALSTACK.getSecretKey()
            )
        );
    }
}
