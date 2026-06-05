package org.mendrugo.attimo.aws;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves human-readable base AMI names (e.g., "fedora-44") to actual
 * AMI IDs in a given region and architecture. Uses DescribeImages with
 * owner/name filters.
 */
public class BaseAmiResolver
{
    // Fedora Cloud images are published by the Fedora project account.
    // Both owner IDs are checked — Fedora has used different accounts over time.
    private static final String[] FEDORA_OWNER_IDS = {
        "125523088429"
        , "013116697141"
    };

    // Cache: "region:arch:name" → AMI ID
    private final Map<String, String> cache = new HashMap<>();

    /**
     * Resolve a base AMI name to an AMI ID.
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
        final var cacheKey = name + ":" + arch;
        final var cached = cache.get(cacheKey);
        if (cached != null)
        {
            return cached;
        }

        final String amiId;
        if (name.startsWith("fedora-"))
        {
            amiId = resolveFedora(name, ec2, arch);
        }
        else
        {
            throw new AwsException(
                "Unknown base AMI: " + name
                + ". Supported: fedora-<version> (e.g., fedora-44)"
            );
        }

        cache.put(cacheKey, amiId);
        return amiId;
    }

    private String resolveFedora(
        final String name
        , final Ec2Client ec2
        , final String arch
    )
    {
        // Parse version from "fedora-44"
        final var parts = name.split("-");
        if (parts.length != 2)
        {
            throw new AwsException("Invalid Fedora AMI name: " + name + ". Expected: fedora-<version>");
        }

        final var version = parts[1];
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
            throw new AwsException(
                "No Fedora " + version + " Cloud AMI found for architecture " + arch
                + " (searched patterns: " + namePatterns
                + ", owners: " + java.util.Arrays.toString(FEDORA_OWNER_IDS) + ")"
            );
        }

        // Pick the most recent image (by creation date)
        final var newest = allImages.stream()
            .max(Comparator.comparing(Image::creationDate))
            .orElseThrow();

        System.out.println("  Resolved " + name + " (" + arch + ") → " + newest.imageId()
            + " (" + newest.name() + ")");

        return newest.imageId();
    }
}
