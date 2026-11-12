package org.mendrugo.attimo.aws.command;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import static org.assertj.core.api.Assertions.assertThat;

class AwsRequestCommandTest {
    @Test
    void insufficientCapacityErrorCodeIsRetryable() {
        Ec2Exception ex = ec2Exception("InsufficientInstanceCapacity", "Insufficient capacity.");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("No spot capacity");
    }

    @Test
    void spotMaxPriceTooLowErrorCodeIsRetryable() {
        Ec2Exception ex = ec2Exception("SpotMaxPriceTooLow", "Spot price too low.");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("Spot price too low");
    }

    @Test
    void maxSpotCountExceededErrorCodeIsRetryable() {
        Ec2Exception ex = ec2Exception("MaxSpotInstanceCountExceeded", "Max spot instance count exceeded");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("Spot instance limit reached");
    }

    @Test
    void invalidAmiNotFoundErrorCodeIsRetryable() {
        Ec2Exception ex = ec2Exception("InvalidAMIID.NotFound", "The image id '[ami-03d517...]' does not exist");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("AMI not found (region-specific)");
    }

    @Test
    void unknownErrorCodeIsNotRetryable() {
        Ec2Exception ex = ec2Exception("UnauthorizedAccess", "Access denied.");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isFalse();
    }

    @Test
    void insufficientCapacityMessageFallbackIsRetryable() {
        RuntimeException ex = new RuntimeException("Insufficient capacity. (Service: Ec2, Status Code: 500)");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("No spot capacity");
    }

    @Test
    void noSpotCapacityMessageFallbackIsRetryable() {
        RuntimeException ex = new RuntimeException("There is no Spot capacity available that matches your request.");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("No spot capacity");
    }

    @Test
    void maxSpotCountMessageFallbackIsRetryable() {
        RuntimeException ex = new RuntimeException("Max spot instance count exceeded");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("Spot instance limit reached");
    }

    @Test
    void imageNotFoundMessageFallbackIsRetryable() {
        RuntimeException ex = new RuntimeException("The image id '[ami-abc123]' does not exist");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isTrue();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("AMI not found (region-specific)");
    }

    @Test
    void nullMessageIsNotRetryable() {
        RuntimeException ex = new RuntimeException((String) null);
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isFalse();
        assertThat(AwsRequestCommand.retryableReason(ex)).isEqualTo("Launch failed");
    }

    @Test
    void unrelatedExceptionIsNotRetryable() {
        RuntimeException ex = new RuntimeException("Connection refused");
        assertThat(AwsRequestCommand.isRetryableLaunchError(ex)).isFalse();
    }

    private static Ec2Exception ec2Exception(String errorCode, String message) {
        return (Ec2Exception) Ec2Exception.builder()
                .message(message)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(errorCode)
                        .errorMessage(message)
                        .serviceName("Ec2")
                        .build())
                .build();
    }
}
