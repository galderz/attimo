package org.mendrugo.attimo.aws;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwsClientFactoryTest {
    @Test
    void createsEc2ClientForRegion() {
        final var factory = new AwsClientFactory();

        final var client = factory.ec2("us-east-1");
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void createsEc2ClientWithEndpointOverride() {
        final var factory = new AwsClientFactory(URI.create("http://localhost:4566"));
        final var client = factory.ec2("us-east-1");
        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void validateCredentialsFailsWithoutRealAws() {

        final var factory = new AwsClientFactory(URI.create("http://localhost:1"));
        final var result = factory.validateCredentials();
        assertThat(result).isNotNull();
        assertThat(result).contains("AWS authentication failed");
    }
}
