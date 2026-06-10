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
import software.amazon.awssdk.services.sts.StsClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.EC2;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.STS;

@Testcontainers
class AwsClientFactoryIT
{
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:4.4")
    )
        .withServices(EC2, STS);

    @Test
    void validateCredentialsAgainstLocalStack()
    {
        try (final var sts = StsClient.builder()
            .endpointOverride(LOCALSTACK.getEndpointOverride(STS))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey()
                        , LOCALSTACK.getSecretKey()
                    )
                )
            )
            .region(Region.of(LOCALSTACK.getRegion()))
            .build())
        {
            final var identity = sts.getCallerIdentity();
            assertThat(identity.account()).isNotBlank();
            assertThat(identity.arn()).isNotBlank();
        }
    }

    @Test
    void ec2ClientWorksAgainstLocalStack()
    {
        try (final var ec2 = Ec2Client.builder()
            .endpointOverride(LOCALSTACK.getEndpointOverride(EC2))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey()
                        , LOCALSTACK.getSecretKey()
                    )
                )
            )
            .region(Region.of(LOCALSTACK.getRegion()))
            .build())
        {
            final var result = ec2.describeRegions();
            assertThat(result.regions()).isNotEmpty();
        }
    }
}
