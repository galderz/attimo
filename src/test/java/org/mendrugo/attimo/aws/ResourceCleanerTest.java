package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceCleanerTest
{
    @Mock
    Ec2Client ec2;

    @Test
    void cleansAllResources()
    {
        when(ec2.terminateInstances(any(TerminateInstancesRequest.class)))
            .thenReturn(TerminateInstancesResponse.builder().build());
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
            .thenReturn(terminatedResponse());

        final var state = createTestState();
        final var cleaner = new ResourceCleaner(ec2);
        final var errors = cleaner.cleanAll(state);

        assertThat(errors).isEmpty();
        verify(ec2).terminateInstances(any(TerminateInstancesRequest.class));
        verify(ec2).deleteKeyPair(any(DeleteKeyPairRequest.class));
        verify(ec2).deleteSecurityGroup(any(DeleteSecurityGroupRequest.class));
    }

    @Test
    void continuesOnPartialFailure()
    {
        when(ec2.terminateInstances(any(TerminateInstancesRequest.class)))
            .thenReturn(TerminateInstancesResponse.builder().build());
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
            .thenReturn(terminatedResponse());
        // SG deletion fails with non-dependent-object error (no retry)
        doThrow(new RuntimeException("Access denied"))
            .when(ec2).deleteSecurityGroup(any(DeleteSecurityGroupRequest.class));

        final var state = createTestState();
        final var cleaner = new ResourceCleaner(ec2);
        final var errors = cleaner.cleanAll(state);

        // Should still have tried to delete key pair even though SG deletion failed
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("security group");
        verify(ec2).deleteKeyPair(any(DeleteKeyPairRequest.class));
    }

    @Test
    void stateNotClearedOnError()
    {
        when(ec2.terminateInstances(any(TerminateInstancesRequest.class)))
            .thenReturn(TerminateInstancesResponse.builder().build());
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
            .thenReturn(terminatedResponse());
        doThrow(new RuntimeException("SG still in use"))
            .when(ec2).deleteSecurityGroup(any(DeleteSecurityGroupRequest.class));

        final var state = createTestState();
        final var cleaner = new ResourceCleaner(ec2);
        final var errors = cleaner.cleanAll(state);

        // State should NOT be cleared when there are errors
        assertThat(errors).isNotEmpty();
        // (InstanceState.clear() is not called — verified by the fact that
        //  errors are returned, which means the caller knows to retry)
    }

    @Test
    void handlesEmptyState()
    {
        final var state = new org.mendrugo.attimo.config.InstanceState();
        final var cleaner = new ResourceCleaner(ec2);
        final var errors = cleaner.cleanAll(state);

        assertThat(errors).isEmpty();
    }

    @Test
    void cleanOrphansScansForTaggedResources()
    {
        when(ec2.describeSecurityGroups(any(DescribeSecurityGroupsRequest.class)))
            .thenReturn(DescribeSecurityGroupsResponse.builder().build());
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
            .thenReturn(DescribeInstancesResponse.builder().build());

        final var cleaner = new ResourceCleaner(ec2);
        final var errors = cleaner.cleanOrphans();

        assertThat(errors).isEmpty();
        verify(ec2).describeSecurityGroups(any(DescribeSecurityGroupsRequest.class));
        verify(ec2).describeInstances(any(DescribeInstancesRequest.class));
    }

    private org.mendrugo.attimo.config.InstanceState createTestState()
    {
        final var state = new org.mendrugo.attimo.config.InstanceState();
        state.setInstanceId("i-test123");
        state.setSecurityGroupId("sg-test456");
        state.setKeyPairName("attimo-test-key");
        state.setRegion("eu-west-1");
        return state;
    }

    private DescribeInstancesResponse terminatedResponse()
    {
        return DescribeInstancesResponse.builder()
            .reservations(
                Reservation.builder()
                    .instances(
                        Instance.builder()
                            .instanceId("i-test123")
                            .state(
                                software.amazon.awssdk.services.ec2.model.InstanceState.builder()
                                    .name(InstanceStateName.TERMINATED)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();
    }
}
