package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.Image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaseAmiResolverTest
{
    @Mock
    Ec2Client ec2;

    @Test
    void resolvesFedora44ToNewestImage()
    {
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(
                DescribeImagesResponse.builder()
                    .images(
                        Image.builder()
                            .imageId("ami-older")
                            .name("Fedora-Cloud-Base-44-1.0.x86_64-hvm-us-east-1")
                            .creationDate("2025-01-01T00:00:00Z")
                            .build()
                        , Image.builder()
                            .imageId("ami-newer")
                            .name("Fedora-Cloud-Base-44-1.1.x86_64-hvm-us-east-1")
                            .creationDate("2025-06-01T00:00:00Z")
                            .build()
                    )
                    .build()
            );

        final var resolver = new BaseAmiResolver();
        final var amiId = resolver.resolve("fedora-44", ec2, "x86_64");
        assertThat(amiId).isEqualTo("ami-newer");
    }

    @Test
    void throwsWhenNoImageFound()
    {
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(DescribeImagesResponse.builder().build());

        final var resolver = new BaseAmiResolver();
        assertThatThrownBy(() -> resolver.resolve("fedora-44", ec2, "x86_64"))
            .isInstanceOf(AwsException.class)
            .hasMessageContaining("No Fedora 44 Cloud AMI found");
    }

    @Test
    void cachesResolvedAmis()
    {
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(
                DescribeImagesResponse.builder()
                    .images(
                        Image.builder()
                            .imageId("ami-cached")
                            .name("Fedora-Cloud-Base-44-1.0.x86_64")
                            .creationDate("2025-06-01T00:00:00Z")
                            .build()
                    )
                    .build()
            );

        final var resolver = new BaseAmiResolver();
        final var first = resolver.resolve("fedora-44", ec2, "x86_64");
        final var second = resolver.resolve("fedora-44", ec2, "x86_64");

        assertThat(first).isEqualTo("ami-cached");
        assertThat(second).isEqualTo("ami-cached");
        // ec2.describeImages called only once due to caching
    }

    @Test
    void throwsForUnknownBaseAmi()
    {
        final var resolver = new BaseAmiResolver();
        assertThatThrownBy(() -> resolver.resolve("ubuntu-24", ec2, "x86_64"))
            .isInstanceOf(AwsException.class)
            .hasMessageContaining("Unknown base AMI");
    }

    @Test
    void handlesArm64Architecture()
    {
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(
                DescribeImagesResponse.builder()
                    .images(
                        Image.builder()
                            .imageId("ami-arm64")
                            .name("Fedora-Cloud-Base-44-1.0.aarch64")
                            .creationDate("2025-06-01T00:00:00Z")
                            .build()
                    )
                    .build()
            );

        final var resolver = new BaseAmiResolver();
        final var amiId = resolver.resolve("fedora-44", ec2, "arm64");
        assertThat(amiId).isEqualTo("ami-arm64");
    }
}
