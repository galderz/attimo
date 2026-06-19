package org.mendrugo.attimo.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

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
    SsmClient ssm;

    @Test
    void resolvesAl2023ForX86()
    {
        when(ssm.getParameter(any(GetParameterRequest.class)))
            .thenReturn(paramResponse("ami-x86"));

        final var resolver = new BaseAmiResolver();
        final var amiId = resolver.resolve(ssm, "x86_64");

        assertThat(amiId).isEqualTo("ami-x86");
        verify(ssm).getParameter(
            GetParameterRequest.builder()
                .name("/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64")
                .build()
        );
    }

    @Test
    void resolvesAl2023ForArm64()
    {
        when(ssm.getParameter(any(GetParameterRequest.class)))
            .thenReturn(paramResponse("ami-arm"));

        final var resolver = new BaseAmiResolver();
        final var amiId = resolver.resolve(ssm, "arm64");

        assertThat(amiId).isEqualTo("ami-arm");
        verify(ssm).getParameter(
            GetParameterRequest.builder()
                .name("/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64")
                .build()
        );
    }

    @Test
    void cachesResolvedAmis()
    {
        when(ssm.getParameter(any(GetParameterRequest.class)))
            .thenReturn(paramResponse("ami-cached"));

        final var resolver = new BaseAmiResolver();
        final var first = resolver.resolve(ssm, "arm64");
        final var second = resolver.resolve(ssm, "arm64");

        assertThat(first).isEqualTo("ami-cached");
        assertThat(second).isEqualTo("ami-cached");
        verify(ssm, times(1)).getParameter(any(GetParameterRequest.class));
    }

    @Test
    void cachesPerArchitecture()
    {
        when(ssm.getParameter(any(GetParameterRequest.class)))
            .thenReturn(paramResponse("ami-arm"))
            .thenReturn(paramResponse("ami-x86"));

        final var resolver = new BaseAmiResolver();
        final var arm = resolver.resolve(ssm, "arm64");
        final var x86 = resolver.resolve(ssm, "x86_64");

        assertThat(arm).isEqualTo("ami-arm");
        assertThat(x86).isEqualTo("ami-x86");
        verify(ssm, times(2)).getParameter(any(GetParameterRequest.class));
    }

    @Test
    void throwsOnSsmFailure()
    {
        when(ssm.getParameter(any(GetParameterRequest.class)))
            .thenThrow(ParameterNotFoundException.builder()
                .message("not found")
                .build());

        final var resolver = new BaseAmiResolver();
        assertThatThrownBy(() -> resolver.resolve(ssm, "arm64"))
            .isInstanceOf(AwsException.class)
            .hasMessageContaining("Failed to resolve Amazon Linux 2023 AMI")
            .hasMessageContaining("arm64");
    }

    @Test
    void sshUserIsEc2User()
    {
        assertThat(BaseAmiResolver.SSH_USER).isEqualTo("ec2-user");
    }

    private GetParameterResponse paramResponse(final String value)
    {
        return GetParameterResponse.builder()
            .parameter(Parameter.builder().value(value).build())
            .build();
    }
}
