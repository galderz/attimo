package org.mendrugo.attimo.aws;

import org.mendrugo.attimo.config.InstanceState;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Ensures all AWS resources created by attimo are cleaned up,
 * leaving zero cost footprint. Each step is best-effort:
 * if one fails, the remaining steps still execute.
 */
public class ResourceCleaner
{
    private final Ec2Client ec2;
    private final List<String> errors = new ArrayList<>();

    public ResourceCleaner(final Ec2Client ec2)
    {
        this.ec2 = ec2;
    }

    /**
     * Clean up all resources from the given instance state.
     *
     * @param state the active instance state
     * @return list of errors (empty if all steps succeeded)
     */
    public List<String> cleanAll(final InstanceState state)
    {
        errors.clear();

        terminateInstance(state.getInstanceId());
        waitForTermination(state.getInstanceId(), 120);
        deleteKeyPair(state.getKeyPairName());
        deleteSecurityGroup(state.getSecurityGroupId());

        InstanceState.clear();

        return List.copyOf(errors);
    }

    private void terminateInstance(final String instanceId)
    {
        if (instanceId.isBlank())
        {
            return;
        }

        try
        {
            System.out.println("  Terminating instance: " + instanceId);
            ec2.terminateInstances(
                TerminateInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build()
            );
        }
        catch (final Exception e)
        {
            errors.add("Failed to terminate instance " + instanceId + ": " + e.getMessage());
        }
    }

    private void waitForTermination(final String instanceId, final int timeoutSeconds)
    {
        if (instanceId.isBlank())
        {
            return;
        }

        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline)
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

                    if (state == InstanceStateName.TERMINATED)
                    {
                        System.out.println("  Instance terminated.");
                        return;
                    }
                }

                Thread.sleep(5000);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            catch (final Exception e)
            {
                // Instance may already be gone
                return;
            }
        }

        System.err.println("  Warning: timed out waiting for instance termination.");
    }

    private void deleteKeyPair(final String keyPairName)
    {
        if (keyPairName.isBlank())
        {
            return;
        }

        try
        {
            System.out.println("  Deleting key pair: " + keyPairName);
            ec2.deleteKeyPair(
                DeleteKeyPairRequest.builder()
                    .keyName(keyPairName)
                    .build()
            );
        }
        catch (final Exception e)
        {
            errors.add("Failed to delete key pair " + keyPairName + ": " + e.getMessage());
        }
    }

    private void deleteSecurityGroup(final String securityGroupId)
    {
        if (securityGroupId.isBlank())
        {
            return;
        }

        try
        {
            System.out.println("  Deleting security group: " + securityGroupId);
            ec2.deleteSecurityGroup(
                DeleteSecurityGroupRequest.builder()
                    .groupId(securityGroupId)
                    .build()
            );
        }
        catch (final Exception e)
        {
            errors.add("Failed to delete security group " + securityGroupId + ": " + e.getMessage());
        }
    }
}
