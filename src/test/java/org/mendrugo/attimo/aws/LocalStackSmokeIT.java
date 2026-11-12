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
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.EC2;

@Testcontainers
class LocalStackSmokeIT {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4"))
            .withServices(EC2);

    @Test
    void createAndDescribeSecurityGroup() {
        Ec2Client ec2 = ec2Client();
        String groupName = "attimo-smoke-test";

        CreateSecurityGroupResponse createResponse = ec2.createSecurityGroup(CreateSecurityGroupRequest.builder()
                .groupName(groupName)
                .description("Smoke test security group")
                .build());

        assertThat(createResponse.groupId()).isNotBlank();

        DescribeSecurityGroupsResponse describeResponse = ec2.describeSecurityGroups(DescribeSecurityGroupsRequest.builder()
                .groupIds(createResponse.groupId())
                .build());

        assertThat(describeResponse.securityGroups()).hasSize(1);
        assertThat(describeResponse.securityGroups()
                .getFirst()
                .groupName())
                .isEqualTo(groupName);

        ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder()
                .groupId(createResponse.groupId())
                .build());
    }

    private Ec2Client ec2Client() {
        return Ec2Client.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(EC2))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build();
    }
}
