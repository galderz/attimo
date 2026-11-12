package org.mendrugo.attimo.aws;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mendrugo.attimo.TestKeys;
import org.mendrugo.attimo.config.InstanceState;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.EC2;

@Testcontainers
class ResourceCleanerIT {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4"))
            .withServices(EC2);

    @Test
    void cleansUpSecurityGroupAndKeyPair() {
        try (Ec2Client ec2 = ec2Client()) {

            final SpotManager manager = new SpotManager(ec2, "it-cleanup-test");
            final String sgId = manager.createSecurityGroup();
            final String dummyPubKey = TestKeys.generateEd25519PublicKey();
            final String keyName = manager.importKeyPair(dummyPubKey);

            assertThat(describeSecurityGroup(ec2, sgId)).isTrue();
            assertThat(describeKeyPair(ec2, keyName)).isTrue();

            final InstanceState state = new InstanceState();
            state.setInstanceId("");
            state.setSecurityGroupId(sgId);
            state.setKeyPairName(keyName);

            final ResourceCleaner cleaner = new ResourceCleaner(ec2);
            final List<String> errors = cleaner.cleanAll(state, "aws");

            assertThat(errors).isEmpty();

            assertThat(describeSecurityGroup(ec2, sgId)).isFalse();
            assertThat(describeKeyPair(ec2, keyName)).isFalse();
        }
    }

    @Test
    void handlesAlreadyDeletedResources() {
        try (Ec2Client ec2 = ec2Client()) {

            final InstanceState state = new InstanceState();
            state.setInstanceId("");
            state.setSecurityGroupId("sg-nonexistent");
            state.setKeyPairName("attimo-nonexistent");

            final ResourceCleaner cleaner = new ResourceCleaner(ec2);
            final List<String> errors = cleaner.cleanAll(state, "aws");

            assertThat(errors).isNotEmpty();
        }
    }

    @Test
    void cleanOrphansFindsTaggedSecurityGroup() {
        try (Ec2Client ec2 = ec2Client()) {

            final SpotManager manager = new SpotManager(ec2, "it-orphan-test");
            final String sgId = manager.createSecurityGroup();

            assertThat(describeSecurityGroup(ec2, sgId)).isTrue();

            final ResourceCleaner cleaner = new ResourceCleaner(ec2);
            final List<String> errors = cleaner.cleanOrphans();

            assertThat(errors).isEmpty();
            assertThat(describeSecurityGroup(ec2, sgId)).isFalse();
        }
    }

    private boolean describeSecurityGroup(Ec2Client ec2, String sgId) {
        try {
            final DescribeSecurityGroupsResponse response = ec2.describeSecurityGroups(DescribeSecurityGroupsRequest.builder()
                    .groupIds(sgId)
                    .build());
            return !response.securityGroups().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean describeKeyPair(Ec2Client ec2, String keyName) {
        try {
            final DescribeKeyPairsResponse response = ec2.describeKeyPairs(DescribeKeyPairsRequest.builder()
                    .keyNames(keyName)
                    .build());
            return !response.keyPairs().isEmpty();
        } catch (Exception e) {
            return false;
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
