package org.mendrugo.attimo.aws;

import java.util.UUID;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.ImportKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceMarketOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.MarketType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.SpotInstanceType;
import software.amazon.awssdk.services.ec2.model.SpotMarketOptions;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

public class SpotManager {
    private final Ec2Client ec2;
    private final String sessionId;

    public SpotManager(Ec2Client ec2) {
        this.ec2 = ec2;
        this.sessionId = UUID.randomUUID().toString();
    }

    public SpotManager(Ec2Client ec2, String sessionId) {
        this.ec2 = ec2;
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String createSecurityGroup() {
        final String groupName = "attimo-" + sessionId.substring(0, 8);

        final CreateSecurityGroupResponse createResponse = ec2.createSecurityGroup(CreateSecurityGroupRequest.builder()
                .groupName(groupName)
                .description("attimo spot instance SSH access")
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.SECURITY_GROUP)
                        .tags(attimoTags())
                        .build())
                .build());

        final String sgId = createResponse.groupId();

        ec2.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(sgId)
                .ipPermissions(
                        IpPermission.builder()
                                .ipProtocol("tcp")
                                .fromPort(22)
                                .toPort(22)
                                .ipRanges(IpRange.builder()
                                        .cidrIp("0.0.0.0/0")
                                        .description("SSH access")
                                        .build())
                                .build())
                .build());

        return sgId;
    }

    public String importKeyPair(String publicKeyContent) {
        final String keyName = "attimo-" + sessionId.substring(0, 8);

        ec2.importKeyPair(ImportKeyPairRequest.builder()
                .keyName(keyName)
                .publicKeyMaterial(SdkBytes.fromUtf8String(publicKeyContent))
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.KEY_PAIR)
                        .tags(attimoTags())
                        .build())
                .build());

        return keyName;
    }

    public String launchSpotInstance(String amiId, String instanceType, String securityGroupId,
            final String keyPairName) {
        final RunInstancesResponse response = ec2.runInstances(
                RunInstancesRequest.builder()
                        .imageId(amiId)
                        .instanceType(instanceType)
                        .keyName(keyPairName)
                        .securityGroupIds(securityGroupId)
                        .instanceMarketOptions(InstanceMarketOptionsRequest.builder()
                                .marketType(MarketType.SPOT)
                                .spotOptions(SpotMarketOptions.builder()
                                        .spotInstanceType(SpotInstanceType.ONE_TIME)
                                        .build())
                                .build())
                        .minCount(1)
                        .maxCount(1)
                        .tagSpecifications(TagSpecification.builder()
                                .resourceType(ResourceType.INSTANCE)
                                .tags(attimoTags())
                                .build())
                        .build());

        final String instanceId = response.instances()
                .getFirst()
                .instanceId();
        return instanceId;
    }

    public String waitForRunning(String instanceId, int timeoutSeconds) {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                final DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build());

                if (!response.reservations().isEmpty() && !response.reservations()
                        .getFirst()
                        .instances()
                        .isEmpty()) {
                    final Instance instance = response.reservations()
                            .getFirst()
                            .instances()
                            .getFirst();
                    final InstanceStateName state = instance.state().name();

                    if (state == InstanceStateName.RUNNING) {
                        final String publicIp = instance.publicIpAddress();
                        if (publicIp != null && !publicIp.isBlank()) {
                            return publicIp;
                        }
                    } else if (state == InstanceStateName.TERMINATED || state == InstanceStateName.SHUTTING_DOWN) {
                        throw new AwsException("Instance " + instanceId + " terminated unexpectedly (state: " + state + ")");
                    }
                }
            } catch (Ec2Exception e) {

                if (e.awsErrorDetails() != null && "InvalidInstanceID.NotFound".equals(e.awsErrorDetails()
                        .errorCode())) {

                } else {
                    throw e;
                }
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("Interrupted while waiting for instance", e);
            }
        }

        throw new AwsException("Timed out waiting for instance " + instanceId + " to reach running state");
    }

    public boolean isRunning(String instanceId) {
        try {
            final DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build());

            if (!response.reservations().isEmpty() && !response.reservations()
                    .getFirst()
                    .instances()
                    .isEmpty()) {
                final InstanceStateName state = response.reservations()
                        .getFirst()
                        .instances()
                        .getFirst()
                        .state()
                        .name();
                return state == InstanceStateName.RUNNING;
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Tag[] attimoTags() {
        return new Tag[] {Tag.builder()
                .key("attimo:managed")
                .value("true")
                .build(),
            Tag.builder()
                .key("attimo:session-id")
                .value(sessionId)
                .build()
        };
    }
}
