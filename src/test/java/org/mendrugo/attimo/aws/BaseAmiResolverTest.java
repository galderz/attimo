package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.Image;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
                            .name("Fedora-Cloud-Base-AmazonEC2.x86_64-44-20250101.0")
                            .creationDate("2025-01-01T00:00:00Z")
                            .build()
                        , Image.builder()
                            .imageId("ami-newer")
                            .name("Fedora-Cloud-Base-AmazonEC2.x86_64-44-20250601.0")
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
    void stopsSearchingAfterFirstPatternMatch()
    {
        // First pattern matches — should not try subsequent patterns
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(
                DescribeImagesResponse.builder()
                    .images(
                        Image.builder()
                            .imageId("ami-found")
                            .name("Fedora-Cloud-Base-AmazonEC2.x86_64-44-20250601.0")
                            .creationDate("2025-06-01T00:00:00Z")
                            .build()
                    )
                    .build()
            );

        final var resolver = new BaseAmiResolver();
        resolver.resolve("fedora-44", ec2, "x86_64");

        // Should only call describeImages once (first pattern matched)
        verify(ec2, times(1)).describeImages(any(DescribeImagesRequest.class));
    }

    @Test
    void fallsBackToSecondPatternWhenFirstEmpty()
    {
        // First pattern returns empty, second returns a match
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(DescribeImagesResponse.builder().build())
            .thenReturn(
                DescribeImagesResponse.builder()
                    .images(
                        Image.builder()
                            .imageId("ami-old-style")
                            .name("Fedora-Cloud-Base-44-1.5.x86_64-hvm-us-east-1")
                            .creationDate("2025-06-01T00:00:00Z")
                            .build()
                    )
                    .build()
            );

        final var resolver = new BaseAmiResolver();
        final var amiId = resolver.resolve("fedora-44", ec2, "x86_64");

        assertThat(amiId).isEqualTo("ami-old-style");
        verify(ec2, times(2)).describeImages(any(DescribeImagesRequest.class));
    }

    @Test
    void throwsWhenNoPatternsMatch()
    {
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(DescribeImagesResponse.builder().build());

        final var resolver = new BaseAmiResolver();
        assertThatThrownBy(() -> resolver.resolve("fedora-44", ec2, "x86_64"))
            .isInstanceOf(AwsException.class)
            .hasMessageContaining("No Fedora 44 Cloud AMI found");

        // Should have tried all 3 patterns
        verify(ec2, times(3)).describeImages(any(DescribeImagesRequest.class));
    }

    @Test
    void searchesMultipleOwnerIds()
    {
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(
                DescribeImagesResponse.builder()
                    .images(
                        Image.builder()
                            .imageId("ami-found")
                            .name("Fedora-Cloud-Base-AmazonEC2.x86_64-44-20250601.0")
                            .creationDate("2025-06-01T00:00:00Z")
                            .build()
                    )
                    .build()
            );

        final var resolver = new BaseAmiResolver();
        resolver.resolve("fedora-44", ec2, "x86_64");

        final var captor = ArgumentCaptor.forClass(DescribeImagesRequest.class);
        verify(ec2).describeImages(captor.capture());

        final var owners = captor.getValue().owners();
        assertThat(owners).contains("125523088429", "013116697141");
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
                            .name("Fedora-Cloud-Base-AmazonEC2.x86_64-44-20250601.0")
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
        verify(ec2, times(1)).describeImages(any(DescribeImagesRequest.class));
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
                            .name("Fedora-Cloud-Base-AmazonEC2.aarch64-44-20250601.0")
                            .creationDate("2025-06-01T00:00:00Z")
                            .build()
                    )
                    .build()
            );

        final var resolver = new BaseAmiResolver();
        final var amiId = resolver.resolve("fedora-44", ec2, "arm64");
        assertThat(amiId).isEqualTo("ami-arm64");

        // Verify the pattern uses aarch64, not arm64
        final var captor = ArgumentCaptor.forClass(DescribeImagesRequest.class);
        verify(ec2).describeImages(captor.capture());

        final var nameFilter = captor.getValue().filters().stream()
            .filter(f -> "name".equals(f.name()))
            .findFirst()
            .orElseThrow();
        assertThat(nameFilter.values().getFirst()).contains("aarch64");
    }

    @Test
    void errorMessageIncludesAllPatternsAndOwners()
    {
        when(ec2.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(DescribeImagesResponse.builder().build());

        final var resolver = new BaseAmiResolver();
        assertThatThrownBy(() -> resolver.resolve("fedora-44", ec2, "x86_64"))
            .isInstanceOf(AwsException.class)
            .hasMessageContaining("searched patterns")
            .hasMessageContaining("owners")
            .hasMessageContaining("125523088429");
    }
}
