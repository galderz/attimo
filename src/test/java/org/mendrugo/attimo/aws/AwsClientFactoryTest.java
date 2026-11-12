package org.mendrugo.attimo.aws;

import java.net.URI;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;

import static org.assertj.core.api.Assertions.assertThat;

class AwsClientFactoryTest {
    @Test
    void createsEc2ClientForRegion() {
        final AwsClientFactory factory = new AwsClientFactory();

        final Ec2Client client = factory.ec2("us-east-1");
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void createsEc2ClientWithEndpointOverride() {
        final AwsClientFactory factory = new AwsClientFactory(URI.create("http://localhost:4566"));
        final Ec2Client client = factory.ec2("us-east-1");
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void validateCredentialsFailsWithoutRealAws() {
        final AwsClientFactory factory = new AwsClientFactory(URI.create("http://localhost:1"));
        final String result = factory.validateCredentials();
        assertThat(result).isNotNull();
        assertThat(result).contains("AWS authentication failed");
    }
}
