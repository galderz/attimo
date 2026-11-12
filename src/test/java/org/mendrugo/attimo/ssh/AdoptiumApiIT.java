package org.mendrugo.attimo.ssh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the grep pattern used by {@link BootJdkProvisioner}
 * correctly extracts the version from the Adoptium API response.
 * This catches format changes (e.g. spacing) before they hit real instances.
 */
class AdoptiumApiIT
{
    private static final String API_URL =
        "https://api.adoptium.net/v3/info/available_releases";

    /**
     * The same regex as the grep -o pattern in BootJdkProvisioner,
     * translated to Java: {@code "most_recent_feature_release": *[0-9]+}
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "\"most_recent_feature_release\":\\s*([0-9]+)"
    );

    @Test
    void grepPatternExtractsVersion() throws IOException, InterruptedException
    {
        final var client = HttpClient.newHttpClient();
        final var request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .GET()
            .build();
        final var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        final var body = response.body();
        final var matcher = VERSION_PATTERN.matcher(body);
        assertThat(matcher.find())
            .as("Pattern should match in API response: %s", body)
            .isTrue();

        final var version = matcher.group(1);
        assertThat(version).matches("[0-9]+");

        final int versionNum = Integer.parseInt(version);
        assertThat(versionNum)
            .as("Latest GA release should be >= 25")
            .isGreaterThanOrEqualTo(25);

        System.out.println("  Adoptium latest GA release: JDK " + version);
    }

    @Test
    void downloadUrlReturnsRedirect() throws IOException, InterruptedException
    {
        // Use the same URL pattern as BootJdkProvisioner
        final var version = latestVersion();
        final var url = String.format(
            "https://api.adoptium.net/v3/binary/latest/%s/ga/linux/x64/jdk/hotspot/normal/eclipse"
            , version
        );

        final var client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        final var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build();
        final var response = client.send(request, HttpResponse.BodyHandlers.discarding());

        // Adoptium API redirects to GitHub releases
        assertThat(response.statusCode())
            .as("Download URL should redirect (307), got %d for %s", response.statusCode(), url)
            .isEqualTo(307);

        System.out.println("  Download URL OK: " + url + " → "
            + response.headers().firstValue("location").orElse("(no location)"));
    }

    private static String latestVersion() throws IOException, InterruptedException
    {
        final var client = HttpClient.newHttpClient();
        final var request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .GET()
            .build();
        final var body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        final var matcher = VERSION_PATTERN.matcher(body);
        if (!matcher.find())
        {
            throw new AssertionError("Could not extract version from: " + body);
        }
        return matcher.group(1);
    }
}
