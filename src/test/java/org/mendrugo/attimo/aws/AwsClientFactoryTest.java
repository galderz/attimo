package org.mendrugo.attimo.aws;

import java.net.URI;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;

import static org.assertj.core.api.Assertions.assertThat;

class AwsClientFactoryTest {
    @Test
    void createsEc2ClientForRegion() {
        AwsClientFactory factory = new AwsClientFactory();

        Ec2Client client = factory.ec2("us-east-1");
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void createsEc2ClientWithEndpointOverride() {
        AwsClientFactory factory = new AwsClientFactory(URI.create("http://localhost:4566"));
        Ec2Client client = factory.ec2("us-east-1");
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void validateCredentialsFailsWithoutRealAws() {
        AwsClientFactory factory = new AwsClientFactory(URI.create("http://localhost:1"));
        String result = factory.validateCredentials();
        assertThat(result).isNotNull();
        assertThat(result).contains("AWS authentication failed");
    }
}
