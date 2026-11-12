package org.mendrugo.attimo.aws;

import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

public class BaseAmiResolver {

    private static final String AL2023_SSM_PARAM = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-";

    public static final String SSH_USER = "ec2-user";

    private final Map<String, String> cache = new HashMap<>();

    public String resolve(SsmClient ssm, String arch, String region) {
        String cacheKey = region + ":" + arch;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String paramName = AL2023_SSM_PARAM + arch;
        System.out.println("  Looking up AMI via SSM: " + paramName);

        try {
            GetParameterResponse response = ssm.getParameter(GetParameterRequest.builder()
                    .name(paramName)
                    .build());

            String amiId = response.parameter().value();
            System.out.println("  Resolved Amazon Linux 2023 (" + arch + ") → " + amiId);
            cache.put(cacheKey, amiId);
            return amiId;
        } catch (Exception e) {
            throw new AwsException("Failed to resolve Amazon Linux 2023 AMI for "
                    + arch
                    + " via SSM parameter "
                    + paramName
                    + ": "
                    + e.getMessage(),
                    e);
        }
    }
}
