package org.mendrugo.attimo.aws;

import org.mendrugo.attimo.config.InstanceState;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Ensures all AWS resources created by attimo are cleaned up,
 * leaving zero cost footprint. Each step is best-effort:
 * if one fails, the remaining steps still execute.
 *
 * State is only cleared when ALL resources are successfully removed.
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
     * State file is only cleared if all resources are successfully removed.
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
        deleteSecurityGroupWithRetry(state.getSecurityGroupId());

        if (errors.isEmpty())
        {
            InstanceState.clear();
        }
        else
        {
            // Keep state file so 'ato destroy' can retry
            System.err.println("  State file kept for retry. Run 'ato destroy' to finish cleanup.");
        }

        return List.copyOf(errors);
    }

    /**
     * Scan for any attimo-tagged resources that may have been orphaned
     * (e.g., after a crash or incomplete cleanup). Cleans them up.
     *
     * @return list of errors (empty if all orphans cleaned or none found)
     */
    public List<String> cleanOrphans()
    {
        errors.clear();
        System.out.println("  Scanning for orphaned attimo resources...");

        cleanOrphanedSecurityGroups();
        cleanOrphanedInstances();

        return List.copyOf(errors);
    }

    private void cleanOrphanedSecurityGroups()
    {
        try
        {
            final var response = ec2.describeSecurityGroups(
                DescribeSecurityGroupsRequest.builder()
                    .filters(
                        Filter.builder()
                            .name("tag:attimo:managed")
                            .values("true")
                            .build()
                    )
                    .build()
            );

            for (final SecurityGroup sg : response.securityGroups())
            {
                System.out.println("  Found orphaned security group: "
                    + sg.groupId() + " (" + sg.groupName() + ")");

                try
                {
                    ec2.deleteSecurityGroup(
                        DeleteSecurityGroupRequest.builder()
                            .groupId(sg.groupId())
                            .build()
                    );
                    System.out.println("  Deleted: " + sg.groupId());
                }
                catch (final Exception e)
                {
                    errors.add("Failed to delete orphaned SG " + sg.groupId() + ": " + e.getMessage());
                }
            }
        }
        catch (final Exception e)
        {
            if (isRegionNotEnabled(e))
            {
                return; // silently skip — region not enabled in account
            }

            errors.add("Failed to scan for orphaned security groups: " + e.getMessage());
        }
    }

    private void cleanOrphanedInstances()
    {
        try
        {
            final var response = ec2.describeInstances(
                DescribeInstancesRequest.builder()
                    .filters(
                        Filter.builder()
                            .name("tag:attimo:managed")
                            .values("true")
                            .build()
                        , Filter.builder()
                            .name("instance-state-name")
                            .values("pending", "running", "stopping", "stopped")
                            .build()
                    )
                    .build()
            );

            for (final var reservation : response.reservations())
            {
                for (final var instance : reservation.instances())
                {
                    System.out.println("  Found orphaned instance: "
                        + instance.instanceId() + " (" + instance.state().name() + ")");

                    try
                    {
                        ec2.terminateInstances(
                            TerminateInstancesRequest.builder()
                                .instanceIds(instance.instanceId())
                                .build()
                        );
                        System.out.println("  Terminated: " + instance.instanceId());
                    }
                    catch (final Exception e)
                    {
                        errors.add("Failed to terminate orphaned instance "
                            + instance.instanceId() + ": " + e.getMessage());
                    }
                }
            }
        }
        catch (final Exception e)
        {
            if (isRegionNotEnabled(e))
            {
                return; // silently skip — region not enabled in account
            }

            errors.add("Failed to scan for orphaned instances: " + e.getMessage());
        }
    }

    /**
     * Check if an exception indicates the region is not enabled in the account.
     * AWS opt-in regions (eu-south-1, eu-south-2, etc.) return 401/AuthFailure
     * when not activated.
     */
    private static boolean isRegionNotEnabled(final Exception e)
    {
        final var msg = e.getMessage();
        if (msg == null)
        {
            return false;
        }

        return msg.contains("401")
            || msg.contains("AuthFailure")
            || msg.contains("OptInRequired");
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

        System.out.println("  Waiting for instance to terminate...");
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

    /**
     * Delete a security group with retries. SG deletion can fail if the
     * instance hasn't fully released its network interface yet.
     */
    private void deleteSecurityGroupWithRetry(final String securityGroupId)
    {
        if (securityGroupId.isBlank())
        {
            return;
        }

        final int maxRetries = 6;
        final int retryDelayMs = 10_000;

        for (int attempt = 1; attempt <= maxRetries; attempt++)
        {
            try
            {
                System.out.println("  Deleting security group: " + securityGroupId
                    + (attempt > 1 ? " (attempt " + attempt + "/" + maxRetries + ")" : ""));

                ec2.deleteSecurityGroup(
                    DeleteSecurityGroupRequest.builder()
                        .groupId(securityGroupId)
                        .build()
                );

                return; // success
            }
            catch (final Exception e)
            {
                if (attempt < maxRetries && e.getMessage() != null
                    && e.getMessage().contains("dependent object"))
                {
                    System.out.println("  Security group still in use, retrying in "
                        + (retryDelayMs / 1000) + "s...");

                    try
                    {
                        Thread.sleep(retryDelayMs);
                    }
                    catch (final InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                        errors.add("Interrupted while waiting to delete security group " + securityGroupId);
                        return;
                    }
                }
                else
                {
                    errors.add("Failed to delete security group " + securityGroupId + ": " + e.getMessage());
                    return;
                }
            }
        }
    }
}
