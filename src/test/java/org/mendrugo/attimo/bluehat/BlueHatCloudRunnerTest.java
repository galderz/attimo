package org.mendrugo.attimo.bluehat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BlueHatCloudRunnerTest
{
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown()
    {
        if (server != null)
        {
            server.stop(0);
        }
    }

    @Test
    void healthCheckSucceedsWithValidResponse()
    {
        server.createContext("/vm", exchange ->
        {
            final var body = "[]";
            exchange.sendResponseHeaders(200, body.length());
            try (final var os = exchange.getResponseBody())
            {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        assertThat(BlueHatCloudRunner.healthCheck("localhost", port)).isTrue();
    }

    @Test
    void healthCheckFailsWhenServerNotRunning()
    {
        // Server not started — connection should fail
        assertThat(BlueHatCloudRunner.healthCheck("localhost", port)).isFalse();
    }

    @Test
    void healthCheckFailsOnNon2xxResponse()
    {
        server.createContext("/vm", exchange ->
        {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        });
        server.start();

        assertThat(BlueHatCloudRunner.healthCheck("localhost", port)).isFalse();
    }

    @Test
    void healthCheckFailsOnUnreachableHost()
    {
        // Unreachable host — should fail without hanging
        assertThat(BlueHatCloudRunner.healthCheck("192.0.2.1", 18080)).isFalse();
    }

    @Test
    void extractRepoNameFromHttpsUrl()
    {
        assertThat(BlueHatCloudRunner.extractRepoName(
            "https://github.com/openjdk/bluehat-cloud.git"
        )).isEqualTo("bluehat-cloud");
    }

    @Test
    void extractRepoNameFromHttpsUrlWithoutGitSuffix()
    {
        assertThat(BlueHatCloudRunner.extractRepoName(
            "https://github.com/openjdk/bluehat-cloud"
        )).isEqualTo("bluehat-cloud");
    }

    @Test
    void extractRepoNameFromUrlWithTrailingSlash()
    {
        assertThat(BlueHatCloudRunner.extractRepoName(
            "https://github.com/openjdk/bluehat-cloud/"
        )).isEqualTo("bluehat-cloud");
    }

    @Test
    void extractRepoNameFromSshUrl()
    {
        assertThat(BlueHatCloudRunner.extractRepoName(
            "git@github.com:openjdk/bluehat-cloud.git"
        )).isEqualTo("bluehat-cloud");
    }

    @Test
    void stopHandlesNullProcess()
    {
        // Should not throw
        BlueHatCloudRunner.stop(null);
    }
}
