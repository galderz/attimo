package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressResponse;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse;
import software.amazon.awssdk.services.ec2.model.ImportKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.ImportKeyPairResponse;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.MarketType;
import software.amazon.awssdk.services.ec2.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpotManagerTest
{
    @Mock
    Ec2Client ec2;

    @Test
    void createSecurityGroupAllowsSSH()
    {
        when(ec2.createSecurityGroup(any(CreateSecurityGroupRequest.class)))
            .thenReturn(
                CreateSecurityGroupResponse.builder()
                    .groupId("sg-test123")
                    .build()
            );
        when(ec2.authorizeSecurityGroupIngress(any(AuthorizeSecurityGroupIngressRequest.class)))
            .thenReturn(AuthorizeSecurityGroupIngressResponse.builder().build());

        final var manager = new SpotManager(ec2, "test-session-id");
        final var sgId = manager.createSecurityGroup();

        assertThat(sgId).isEqualTo("sg-test123");

        final var ingressCaptor = ArgumentCaptor.forClass(AuthorizeSecurityGroupIngressRequest.class);
        verify(ec2).authorizeSecurityGroupIngress(ingressCaptor.capture());

        final var ingress = ingressCaptor.getValue();
        assertThat(ingress.ipPermissions()).hasSize(1);
        assertThat(ingress.ipPermissions().getFirst().fromPort()).isEqualTo(22);
        assertThat(ingress.ipPermissions().getFirst().toPort()).isEqualTo(22);
    }

    @Test
    void securityGroupTaggedWithAttimoMetadata()
    {
        when(ec2.createSecurityGroup(any(CreateSecurityGroupRequest.class)))
            .thenReturn(CreateSecurityGroupResponse.builder().groupId("sg-test").build());
        when(ec2.authorizeSecurityGroupIngress(any(AuthorizeSecurityGroupIngressRequest.class)))
            .thenReturn(AuthorizeSecurityGroupIngressResponse.builder().build());

        final var manager = new SpotManager(ec2, "test-session-id");
        manager.createSecurityGroup();

        final var captor = ArgumentCaptor.forClass(CreateSecurityGroupRequest.class);
        verify(ec2).createSecurityGroup(captor.capture());

        final var tags = captor.getValue().tagSpecifications().getFirst().tags();
        assertThat(tags).extracting(Tag::key).contains("attimo:managed", "attimo:session-id");
    }

    @Test
    void launchSpotInstanceUsesModernApi()
    {
        when(ec2.runInstances(any(RunInstancesRequest.class)))
            .thenReturn(
                RunInstancesResponse.builder()
                    .instances(
                        Instance.builder()
                            .instanceId("i-test123")
                            .build()
                    )
                    .build()
            );

        final var manager = new SpotManager(ec2, "test-session-id");
        final var instanceId = manager.launchSpotInstance(
            "ami-test"
            , "c7i.xlarge"
            , "sg-test"
            , "attimo-key"
        );

        assertThat(instanceId).isEqualTo("i-test123");

        final var captor = ArgumentCaptor.forClass(RunInstancesRequest.class);
        verify(ec2).runInstances(captor.capture());

        final var request = captor.getValue();
        assertThat(request.imageId()).isEqualTo("ami-test");
        assertThat(request.instanceTypeAsString()).isEqualTo("c7i.xlarge");
        assertThat(request.keyName()).isEqualTo("attimo-key");
        assertThat(request.securityGroupIds()).contains("sg-test");
        assertThat(request.instanceMarketOptions().marketType()).isEqualTo(MarketType.SPOT);
    }

    @Test
    void launchTagsInstanceWithAttimoMetadata()
    {
        when(ec2.runInstances(any(RunInstancesRequest.class)))
            .thenReturn(
                RunInstancesResponse.builder()
                    .instances(Instance.builder().instanceId("i-test").build())
                    .build()
            );

        final var manager = new SpotManager(ec2, "test-session-id");
        manager.launchSpotInstance("ami-test", "c7i.xlarge", "sg-test", "key-test");

        final var captor = ArgumentCaptor.forClass(RunInstancesRequest.class);
        verify(ec2).runInstances(captor.capture());

        final var tags = captor.getValue().tagSpecifications().getFirst().tags();
        assertThat(tags).extracting(Tag::key).contains("attimo:managed", "attimo:session-id");
        assertThat(tags).extracting(Tag::value).contains("true", "test-session-id");
    }

    @Test
    void sessionIdIsPreserved()
    {
        final var manager = new SpotManager(ec2, "my-session");
        assertThat(manager.sessionId()).isEqualTo("my-session");
    }
}
