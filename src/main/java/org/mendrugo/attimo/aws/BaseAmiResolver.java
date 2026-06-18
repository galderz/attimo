package org.mendrugo.attimo.aws;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves human-readable base AMI names (e.g., "fedora-44") to actual
 * AMI IDs in a given region and architecture. Uses DescribeImages with
 * owner/name filters.
 *
 * <p>When the requested Fedora version is not available in the target
 * region (common in newer/opt-in regions like eu-central-2), falls back
 * to earlier Fedora versions (44 → 43 → 42 → 41), then to Amazon Linux
 * 2023 as a last resort.</p>
 */
public class BaseAmiResolver
{
    // Fedora Cloud images are published by the Fedora project account.
    // Both owner IDs are checked — Fedora has used different accounts over time.
    private static final String[] FEDORA_OWNER_IDS = {
        "125523088429"
        , "013116697141"
    };

    // SSM parameter paths for Amazon Linux 2023 AMI lookup (AWS-recommended)
    private static final String AL2023_SSM_PARAM_PREFIX =
        "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-";

    // How many earlier Fedora versions to try before falling back.
    // Limited to 1 because JDK packages (e.g., java-25-openjdk-devel)
    // are only available on the current and previous Fedora release.
    static final int FEDORA_FALLBACK_VERSIONS = 1;

    // Cache: "arch:resolvedName" → AmiResult
    private final Map<String, AmiResult> cache = new HashMap<>();

    /**
     * Result of AMI resolution, including metadata needed for provisioning.
     */
    public record AmiResult(
        String amiId
        , String resolvedName
        , String sshUser
        , boolean isFallback
    ) {}

    /**
     * Resolve a base AMI name to an AMI ID, with version and OS fallback.
     *
     * <p>Fallback order for "fedora-44":
     * <ol>
     *   <li>Fedora 44 in target region</li>
     *   <li>Fedora 43, 42, 41 in target region</li>
     *   <li>Amazon Linux 2023 in target region (always available)</li>
     * </ol>
     *
     * @param name       the base AMI name (e.g., "fedora-44")
     * @param ec2        the EC2 client for the target region
     * @param arch       the target architecture ("x86_64" or "arm64")
     * @param ssmClient  SSM client for the target region (for Amazon Linux lookup)
     * @return the resolution result with AMI ID and metadata
     * @throws AwsException if no matching AMI is found at all
     */
    public AmiResult resolveWithFallback(
        final String name
        , final Ec2Client ec2
        , final String arch
        , final SsmClient ssmClient
    )
    {
        final var cacheKey = name + ":" + arch;
        final var cached = cache.get(cacheKey);
        if (cached != null)
        {
            return cached;
        }

        if (!name.startsWith("fedora-"))
        {
            throw new AwsException(
                "Unknown base AMI: " + name
                + ". Supported: fedora-<version> (e.g., fedora-44)"
            );
        }

        final var parts = name.split("-");
        if (parts.length != 2)
        {
            throw new AwsException(
                "Invalid Fedora AMI name: " + name + ". Expected: fedora-<version>"
            );
        }

        final var requestedVersion = Integer.parseInt(parts[1]);

        // Try requested version first, then fall back to earlier versions
        for (int v = requestedVersion; v >= requestedVersion - FEDORA_FALLBACK_VERSIONS; v--)
        {
            final var amiId = searchFedora(v, ec2, arch);
            if (amiId != null)
            {
                final var isFallback = v != requestedVersion;
                if (isFallback)
                {
                    System.out.println("  Note: Fedora " + requestedVersion
                        + " not available, using Fedora " + v + " instead");
                }

                final var result = new AmiResult(
                    amiId, "fedora-" + v, "fedora", isFallback
                );
                cache.put(cacheKey, result);
                return result;
            }
        }

        // No Fedora version found — try Amazon Linux 2023 via SSM
        System.out.println("  No Fedora AMI available in this region, trying Amazon Linux 2023...");
        final var al2023Id = searchAmazonLinux2023(ssmClient, arch);
        if (al2023Id != null)
        {
            System.out.println("  WARNING: Using Amazon Linux 2023 instead of Fedora.");
            System.out.println("  Some packages (e.g., java-25-openjdk-devel) may not be available.");
            System.out.println("  Consider using a region where Fedora AMIs are published.");

            final var result = new AmiResult(al2023Id, "al2023", "ec2-user", true);
            cache.put(cacheKey, result);
            return result;
        }

        throw new AwsException(
            "No suitable AMI found for architecture " + arch
            + " in this region. Tried Fedora " + requestedVersion
            + (FEDORA_FALLBACK_VERSIONS > 0
                ? " through " + (requestedVersion - FEDORA_FALLBACK_VERSIONS)
                : "")
            + " and Amazon Linux 2023."
        );
    }

    /**
     * Resolve a base AMI name to an AMI ID (legacy method, no fallback,
     * no Amazon Linux 2023 fallback since no SSM client).
     *
     * @param name   the base AMI name (e.g., "fedora-44")
     * @param ec2    the EC2 client for the target region
     * @param arch   the target architecture ("x86_64" or "arm64")
     * @return the AMI ID
     * @throws AwsException if no matching AMI is found
     */
    public String resolve(
        final String name
        , final Ec2Client ec2
        , final String arch
    )
    {
        final var result = resolveWithFallback(name, ec2, arch, null);
        return result.amiId();
    }

    /**
     * Search for a specific Fedora version AMI.
     *
     * @return AMI ID if found, null otherwise
     */
    String searchFedora(
        final int version
        , final Ec2Client ec2
        , final String arch
    )
    {
        final var fedoraArch = "arm64".equals(arch) ? "aarch64" : arch;

        // Fedora Cloud AMI naming has changed over time:
        //   Old:  Fedora-Cloud-Base-39-1.5.x86_64-hvm-us-east-1-gp3-0
        //   New:  Fedora-Cloud-Base-AmazonEC2.x86_64-44-20260501.0
        // Try multiple patterns to handle both conventions.
        final var namePatterns = List.of(
            "Fedora-Cloud-Base-AmazonEC2." + fedoraArch + "-" + version + "-*"
            , "Fedora-Cloud-Base-" + version + "-*." + fedoraArch + "-*"
            , "Fedora-Cloud-Base-" + version + "*" + fedoraArch + "*"
        );

        final var allImages = new java.util.ArrayList<Image>();

        for (final String pattern : namePatterns)
        {
            System.out.println("  Searching for: " + pattern);

            try
            {
                final var response = ec2.describeImages(
                    DescribeImagesRequest.builder()
                        .owners(FEDORA_OWNER_IDS)
                        .filters(
                            Filter.builder()
                                .name("name")
                                .values(pattern)
                                .build()
                            , Filter.builder()
                                .name("state")
                                .values("available")
                                .build()
                            , Filter.builder()
                                .name("architecture")
                                .values(arch)
                                .build()
                        )
                        .build()
                );

                allImages.addAll(response.images());
            }
            catch (final Exception e)
            {
                System.err.println("  Warning: AMI search failed for pattern " + pattern
                    + ": " + e.getMessage());
            }

            if (!allImages.isEmpty())
            {
                break;
            }
        }

        if (allImages.isEmpty())
        {
            return null;
        }

        // Pick the most recent image (by creation date)
        final var newest = allImages.stream()
            .max(Comparator.comparing(Image::creationDate))
            .orElseThrow();

        System.out.println("  Resolved fedora-" + version + " (" + arch + ") → "
            + newest.imageId() + " (" + newest.name() + ")");

        return newest.imageId();
    }

    /**
     * Look up the latest Amazon Linux 2023 AMI via SSM Parameter Store.
     * This is the AWS-recommended approach and works in every region.
     *
     * <p>Parameter path:
     * {@code /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-<arch>}
     *
     * @return AMI ID if found, null otherwise
     */
    String searchAmazonLinux2023(
        final SsmClient ssmClient
        , final String arch
    )
    {
        if (ssmClient == null)
        {
            return null;
        }

        final var paramName = AL2023_SSM_PARAM_PREFIX + arch;
        System.out.println("  Looking up SSM parameter: " + paramName);

        try
        {
            final var response = ssmClient.getParameter(
                GetParameterRequest.builder()
                    .name(paramName)
                    .build()
            );

            final var amiId = response.parameter().value();
            System.out.println("  Resolved Amazon Linux 2023 (" + arch + ") → " + amiId);
            return amiId;
        }
        catch (final Exception e)
        {
            System.err.println("  Warning: Amazon Linux 2023 SSM lookup failed: "
                + e.getMessage());
            return null;
        }
    }
}
