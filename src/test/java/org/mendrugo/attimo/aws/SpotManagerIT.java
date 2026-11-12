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
import software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.EC2;

@Testcontainers
class SpotManagerIT
{
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:4.4")
    )
        .withServices(EC2);

    @Test
    void createSecurityGroupWithSshIngress()
    {
        try (final var ec2 = ec2Client())
        {
            final var manager = new SpotManager(ec2, "it-test-session");
            final var sgId = manager.createSecurityGroup();

            assertThat(sgId).isNotBlank();

            final var describeResponse = ec2.describeSecurityGroups(
                DescribeSecurityGroupsRequest.builder()
                    .groupIds(sgId)
                    .build()
            );

            assertThat(describeResponse.securityGroups()).hasSize(1);
            final var sg = describeResponse.securityGroups().getFirst();
            assertThat(sg.groupName()).startsWith("attimo-");

            assertThat(sg.ipPermissions()).isNotEmpty();
            final var sshRule = sg.ipPermissions().getFirst();
            assertThat(sshRule.fromPort()).isEqualTo(22);
            assertThat(sshRule.toPort()).isEqualTo(22);

            ec2.deleteSecurityGroup(
                DeleteSecurityGroupRequest.builder()
                    .groupId(sgId)
                    .build()
            );
        }
    }

    @Test
    void importKeyPairAndVerify()
    {
        try (final var ec2 = ec2Client())
        {
            final var manager = new SpotManager(ec2, "it-test-session");
            final var dummyPubKey = org.mendrugo.attimo.TestKeys.generateEd25519PublicKey();
            final var keyName = manager.importKeyPair(dummyPubKey);

            assertThat(keyName).startsWith("attimo-");

            final var describeResponse = ec2.describeKeyPairs(
                DescribeKeyPairsRequest.builder()
                    .keyNames(keyName)
                    .build()
            );

            assertThat(describeResponse.keyPairs()).hasSize(1);
            assertThat(describeResponse.keyPairs().getFirst().keyName()).isEqualTo(keyName);

            ec2.deleteKeyPair(
                DeleteKeyPairRequest.builder()
                    .keyName(keyName)
                    .build()
            );
        }
    }

    private Ec2Client ec2Client()
    {
        return Ec2Client.builder()
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
            .build();
    }
}
