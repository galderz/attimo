package org.mendrugo.attimo.ssh;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdoptiumApiIT {
    private static final String API_URL = "https://api.adoptium.net/v3/info/available_releases";

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"most_recent_feature_release\":\\s*([0-9]+)");

    @Test
    void grepPatternExtractsVersion() throws IOException, InterruptedException {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .GET()
                .build();
        final HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        final String body = response.body();
        final Matcher matcher = VERSION_PATTERN.matcher(body);
        assertThat(matcher.find())
                .as("Pattern should match in API response: %s", body)
                .isTrue();

        final String version = matcher.group(1);
        assertThat(version).matches("[0-9]+");

        final int versionNum = Integer.parseInt(version);
        assertThat(versionNum)
                .as("Latest GA release should be >= 25")
                .isGreaterThanOrEqualTo(25);

        System.out.println("  Adoptium latest GA release: JDK " + version);
    }

    @Test
    void downloadUrlReturnsRedirect() throws IOException, InterruptedException {
        final String version = latestVersion();
        final String url = String.format("https://api.adoptium.net/v3/binary/latest/%s/ga/linux/x64/jdk/hotspot/normal/eclipse", version);

        final HttpClient client = HttpClient.newBuilder()
                .followRedirects(Redirect.NEVER)
                .build();
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("HEAD", BodyPublishers.noBody())
                .build();
        final HttpResponse<Void> response = client.send(request, BodyHandlers.discarding());

        assertThat(response.statusCode())
                .as("Download URL should redirect (307), got %d for %s", response.statusCode(), url)
                .isEqualTo(307);

        System.out.println("  Download URL OK: " + url + " → " + response.headers()
                .firstValue("location")
                .orElse("(no location)"));
    }

    private static String latestVersion() throws IOException, InterruptedException {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .GET()
                .build();
        final String body = client.send(request, BodyHandlers.ofString()).body();
        final Matcher matcher = VERSION_PATTERN.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Could not extract version from: " + body);
        }
        return matcher.group(1);
    }
}
