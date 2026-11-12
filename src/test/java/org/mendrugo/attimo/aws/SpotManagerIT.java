package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.mendrugo.attimo.TestKeys;
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
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.EC2;

@Testcontainers
class SpotManagerIT {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4"))
            .withServices(EC2);

    @Test
    void createSecurityGroupWithSshIngress() {
        try (Ec2Client ec2 = ec2Client()) {
            SpotManager manager = new SpotManager(ec2, "it-test-session");
            String sgId = manager.createSecurityGroup();

            assertThat(sgId).isNotBlank();

            DescribeSecurityGroupsResponse describeResponse = ec2.describeSecurityGroups(DescribeSecurityGroupsRequest.builder()
                    .groupIds(sgId)
                    .build());

            assertThat(describeResponse.securityGroups()).hasSize(1);
            SecurityGroup sg = describeResponse.securityGroups().getFirst();
            assertThat(sg.groupName()).startsWith("attimo-");

            assertThat(sg.ipPermissions()).isNotEmpty();
            IpPermission sshRule = sg.ipPermissions().getFirst();
            assertThat(sshRule.fromPort()).isEqualTo(22);
            assertThat(sshRule.toPort()).isEqualTo(22);

            ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder()
                    .groupId(sgId)
                    .build());
        }
    }

    @Test
    void importKeyPairAndVerify() {
        try (Ec2Client ec2 = ec2Client()) {
            SpotManager manager = new SpotManager(ec2, "it-test-session");
            String dummyPubKey = TestKeys.generateEd25519PublicKey();
            String keyName = manager.importKeyPair(dummyPubKey);

            assertThat(keyName).startsWith("attimo-");

            DescribeKeyPairsResponse describeResponse = ec2.describeKeyPairs(DescribeKeyPairsRequest.builder()
                    .keyNames(keyName)
                    .build());

            assertThat(describeResponse.keyPairs()).hasSize(1);
            assertThat(describeResponse.keyPairs()
                    .getFirst()
                    .keyName())
                    .isEqualTo(keyName);

            ec2.deleteKeyPair(DeleteKeyPairRequest.builder()
                    .keyName(keyName)
                    .build());
        }
    }

    private Ec2Client ec2Client() {
        return Ec2Client.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(EC2))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build();
    }
}
