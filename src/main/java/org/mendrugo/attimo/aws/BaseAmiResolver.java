package org.mendrugo.attimo.aws;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the base AMI for a given architecture using SSM Parameter Store.
 * Uses Amazon Linux 2023, which is available in every AWS region.
 */
public class BaseAmiResolver
{
    // SSM parameter path for Amazon Linux 2023 AMI lookup (AWS-recommended)
    private static final String AL2023_SSM_PARAM =
        "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-";

    public static final String SSH_USER = "ec2-user";

    // Cache: arch → AMI ID
    private final Map<String, String> cache = new HashMap<>();

    /**
     * Resolve the Amazon Linux 2023 AMI for the given architecture.
     *
     * @param ssm  SSM client for the target region
     * @param arch the target architecture ("x86_64" or "arm64")
     * @return the AMI ID
     * @throws AwsException if the AMI cannot be resolved
     */
    public String resolve(
        final SsmClient ssm
        , final String arch
    )
    {
        final var cached = cache.get(arch);
        if (cached != null)
        {
            return cached;
        }

        final var paramName = AL2023_SSM_PARAM + arch;
        System.out.println("  Looking up AMI via SSM: " + paramName);

        try
        {
            final var response = ssm.getParameter(
                GetParameterRequest.builder()
                    .name(paramName)
                    .build()
            );

            final var amiId = response.parameter().value();
            System.out.println("  Resolved Amazon Linux 2023 (" + arch + ") → " + amiId);
            cache.put(arch, amiId);
            return amiId;
        }
        catch (final Exception e)
        {
            throw new AwsException(
                "Failed to resolve Amazon Linux 2023 AMI for " + arch
                + " via SSM parameter " + paramName + ": " + e.getMessage()
                , e
            );
        }
    }
}
