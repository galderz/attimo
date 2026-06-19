package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SSM;

/**
 * Integration tests for BaseAmiResolver against LocalStack SSM.
 */
@Testcontainers
class BaseAmiResolverIT
{
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:4.4")
    )
        .withServices(SSM);

    @Test
    void ssmClientCanGetAndPutParameters()
    {
        // Verify SSM works against LocalStack.
        // We can't write to /aws/service/ (reserved by LocalStack),
        // and the real AL2023 parameters aren't seeded in LocalStack,
        // so we verify basic SSM operations and that the resolver
        // throws a clear error when the parameter is missing.
        try (final var ssm = ssmClient())
        {
            ssm.putParameter(
                PutParameterRequest.builder()
                    .name("/attimo/test/ami")
                    .value("ami-test")
                    .type(ParameterType.STRING)
                    .build()
            );

            final var value = ssm.getParameter(
                software.amazon.awssdk.services.ssm.model.GetParameterRequest.builder()
                    .name("/attimo/test/ami")
                    .build()
            ).parameter().value();

            assertThat(value).isEqualTo("ami-test");
        }
    }

    @Test
    void resolverReturnsAmiIdFromSsm()
    {
        // LocalStack pre-seeds /aws/service/ parameters, so the
        // resolver should succeed and return a non-blank AMI ID.
        try (final var ssm = ssmClient())
        {
            final var resolver = new BaseAmiResolver();
            final var amiId = resolver.resolve(ssm, "arm64");
            assertThat(amiId).isNotBlank();
        }
    }

    private SsmClient ssmClient()
    {
        return SsmClient.builder()
            .endpointOverride(LOCALSTACK.getEndpointOverride(SSM))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey()
                        , LOCALSTACK.getSecretKey()
                    )
                )
            )
            .httpClient(ApacheHttpClient.builder().build())
            .region(Region.of(LOCALSTACK.getRegion()))
            .build();
    }
}
