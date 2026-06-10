package org.mendrugo.attimo.aws;

import org.mendrugo.attimo.config.InstanceState;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.ImportKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.InstanceMarketOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.MarketType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.SpotMarketOptions;
import software.amazon.awssdk.services.ec2.model.SpotInstanceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.core.SdkBytes;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Manages the lifecycle of a spot instance: create security group,
 * import SSH key pair, launch instance, wait for ready, terminate.
 */
public class SpotManager
{
    private final Ec2Client ec2;
    private final String sessionId;

    public SpotManager(final Ec2Client ec2)
    {
        this.ec2 = ec2;
        this.sessionId = UUID.randomUUID().toString();
    }

    public SpotManager(final Ec2Client ec2, final String sessionId)
    {
        this.ec2 = ec2;
        this.sessionId = sessionId;
    }

    public String sessionId()
    {
        return sessionId;
    }

    /**
     * Create a security group allowing SSH (port 22) from anywhere.
     *
     * @return the security group ID
     */
    public String createSecurityGroup()
    {
        final var groupName = "attimo-" + sessionId.substring(0, 8);

        final var createResponse = ec2.createSecurityGroup(
            CreateSecurityGroupRequest.builder()
                .groupName(groupName)
                .description("attimo spot instance SSH access")
                .tagSpecifications(
                    TagSpecification.builder()
                        .resourceType(ResourceType.SECURITY_GROUP)
                        .tags(attimoTags())
                        .build()
                )
                .build()
        );

        final var sgId = createResponse.groupId();

        ec2.authorizeSecurityGroupIngress(
            AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(sgId)
                .ipPermissions(
                    IpPermission.builder()
                        .ipProtocol("tcp")
                        .fromPort(22)
                        .toPort(22)
                        .ipRanges(
                            IpRange.builder()
                                .cidrIp("0.0.0.0/0")
                                .description("SSH access")
                                .build()
                        )
                        .build()
                )
                .build()
        );

        System.out.println("  Created security group: " + sgId + " (" + groupName + ")");
        return sgId;
    }

    /**
     * Import an SSH public key as an EC2 key pair.
     *
     * @param publicKeyContent the SSH public key content
     * @return the key pair name
     */
    public String importKeyPair(final String publicKeyContent)
    {
        final var keyName = "attimo-" + sessionId.substring(0, 8);

        ec2.importKeyPair(
            ImportKeyPairRequest.builder()
                .keyName(keyName)
                .publicKeyMaterial(SdkBytes.fromUtf8String(publicKeyContent))
                .tagSpecifications(
                    TagSpecification.builder()
                        .resourceType(ResourceType.KEY_PAIR)
                        .tags(attimoTags())
                        .build()
                )
                .build()
        );

        System.out.println("  Imported key pair: " + keyName);
        return keyName;
    }

    /**
     * Launch a spot instance.
     *
     * @param amiId          the AMI to launch from
     * @param instanceType   the instance type (e.g., "c7i.xlarge")
     * @param securityGroupId the security group ID
     * @param keyPairName    the key pair name
     * @return the instance ID
     */
    public String launchSpotInstance(
        final String amiId
        , final String instanceType
        , final String securityGroupId
        , final String keyPairName
    )
    {
        final var response = ec2.runInstances(
            RunInstancesRequest.builder()
                .imageId(amiId)
                .instanceType(instanceType)
                .keyName(keyPairName)
                .securityGroupIds(securityGroupId)
                .instanceMarketOptions(
                    InstanceMarketOptionsRequest.builder()
                        .marketType(MarketType.SPOT)
                        .spotOptions(
                            SpotMarketOptions.builder()
                                .spotInstanceType(SpotInstanceType.ONE_TIME)
                                .build()
                        )
                        .build()
                )
                .minCount(1)
                .maxCount(1)
                .tagSpecifications(
                    TagSpecification.builder()
                        .resourceType(ResourceType.INSTANCE)
                        .tags(attimoTags())
                        .build()
                )
                .build()
        );

        final var instanceId = response.instances().getFirst().instanceId();
        System.out.println("  Launched spot instance: " + instanceId + " (" + instanceType + ")");
        return instanceId;
    }

    /**
     * Wait for an instance to reach the running state.
     *
     * @param instanceId the instance to wait for
     * @param timeoutSeconds maximum time to wait
     * @return the public IP address
     */
    public String waitForRunning(
        final String instanceId
        , final int timeoutSeconds
    )
    {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline)
        {
            final var response = ec2.describeInstances(
                DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build()
            );

            if (!response.reservations().isEmpty()
                && !response.reservations().getFirst().instances().isEmpty())
            {
                final var instance = response.reservations().getFirst().instances().getFirst();
                final var state = instance.state().name();

                if (state == InstanceStateName.RUNNING)
                {
                    final var publicIp = instance.publicIpAddress();
                    if (publicIp != null && !publicIp.isBlank())
                    {
                        System.out.println("  Instance running: " + publicIp);
                        return publicIp;
                    }
                }
                else if (state == InstanceStateName.TERMINATED
                    || state == InstanceStateName.SHUTTING_DOWN)
                {
                    throw new AwsException(
                        "Instance " + instanceId + " terminated unexpectedly (state: " + state + ")"
                    );
                }
            }

            try
            {
                Thread.sleep(5000);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new AwsException("Interrupted while waiting for instance", e);
            }
        }

        throw new AwsException(
            "Timed out waiting for instance " + instanceId + " to reach running state"
        );
    }

    /**
     * Check if an instance is still running.
     *
     * @param instanceId the instance to check
     * @return true if running, false otherwise
     */
    public boolean isRunning(final String instanceId)
    {
        try
        {
            final var response = ec2.describeInstances(
                DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build()
            );

            if (!response.reservations().isEmpty()
                && !response.reservations().getFirst().instances().isEmpty())
            {
                final var state = response.reservations().getFirst()
                    .instances().getFirst().state().name();
                return state == InstanceStateName.RUNNING;
            }

            return false;
        }
        catch (final Exception e)
        {
            return false;
        }
    }

    private Tag[] attimoTags()
    {
        return new Tag[]{
            Tag.builder().key("attimo:managed").value("true").build()
            , Tag.builder().key("attimo:session-id").value(sessionId).build()
        };
    }
}
