package org.mendrugo.attimo.aws;

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
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.EC2;

@Testcontainers
class ResourceCleanerIT {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4"))
            .withServices(EC2);

    @Test
    void cleansUpSecurityGroupAndKeyPair() {
        try (final var ec2 = ec2Client()) {

            final var manager = new SpotManager(ec2, "it-cleanup-test");
            final var sgId = manager.createSecurityGroup();
            final var dummyPubKey = TestKeys.generateEd25519PublicKey();
            final var keyName = manager.importKeyPair(dummyPubKey);

            assertThat(describeSecurityGroup(ec2, sgId)).isTrue();
            assertThat(describeKeyPair(ec2, keyName)).isTrue();

            final var state = new InstanceState();
            state.setInstanceId("");
            state.setSecurityGroupId(sgId);
            state.setKeyPairName(keyName);

            final var cleaner = new ResourceCleaner(ec2);
            final var errors = cleaner.cleanAll(state, "aws");

            assertThat(errors).isEmpty();

            assertThat(describeSecurityGroup(ec2, sgId)).isFalse();
            assertThat(describeKeyPair(ec2, keyName)).isFalse();
        }
    }

    @Test
    void handlesAlreadyDeletedResources() {
        try (final var ec2 = ec2Client()) {

            final var state = new InstanceState();
            state.setInstanceId("");
            state.setSecurityGroupId("sg-nonexistent");
            state.setKeyPairName("attimo-nonexistent");

            final var cleaner = new ResourceCleaner(ec2);
            final var errors = cleaner.cleanAll(state, "aws");

            assertThat(errors).isNotEmpty();
        }
    }

    @Test
    void cleanOrphansFindsTaggedSecurityGroup() {
        try (final var ec2 = ec2Client()) {

            final var manager = new SpotManager(ec2, "it-orphan-test");
            final var sgId = manager.createSecurityGroup();

            assertThat(describeSecurityGroup(ec2, sgId)).isTrue();

            final var cleaner = new ResourceCleaner(ec2);
            final var errors = cleaner.cleanOrphans();

            assertThat(errors).isEmpty();
            assertThat(describeSecurityGroup(ec2, sgId)).isFalse();
        }
    }

    private boolean describeSecurityGroup(final Ec2Client ec2, final String sgId) {
        try {
            final var response = ec2.describeSecurityGroups(DescribeSecurityGroupsRequest.builder()
                    .groupIds(sgId)
                    .build());
            return !response.securityGroups().isEmpty();
        } catch (final Exception e) {
            return false;
        }
    }

    private boolean describeKeyPair(final Ec2Client ec2, final String keyName) {
        try {
            final var response = ec2.describeKeyPairs(DescribeKeyPairsRequest.builder()
                    .keyNames(keyName)
                    .build());
            return !response.keyPairs().isEmpty();
        } catch (final Exception e) {
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
