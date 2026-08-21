package org.mendrugo.attimo.bluehat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlueHatClientTest
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
    void requestVmReturnsFqdn()
    {
        server.createContext("/vm", exchange ->
        {
            if ("POST".equals(exchange.getRequestMethod()))
            {
                // Verify request body is consumed
                final var body = new String(
                    exchange.getRequestBody().readAllBytes()
                    , StandardCharsets.UTF_8
                );
                assertThat(body).contains("\"cpu\"");
                assertThat(body).contains("\"memory\"");
                assertThat(body).contains("\"os\"");
                assertThat(body).contains("\"ssh-public-key\"");

                final var response = """
                    {"fqdn": "bluehat-vm-12345.dev.acme.com"}""";
                exchange.sendResponseHeaders(200, response.length());
                try (final var os = exchange.getResponseBody())
                {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();

        final var client = new BlueHatClient("localhost", port);
        final var request = new BlueHatClient.VmRequest(
            "16"
            , "32"
            , "RedHat 10.2"
            , "test vm"
            , "ssh-ed25519 AAAA..."
        );

        final var response = client.requestVm(request);
        assertThat(response.fqdn()).isEqualTo("bluehat-vm-12345.dev.acme.com");
    }

    @Test
    void requestVmThrowsOnHttpError()
    {
        server.createContext("/vm", exchange ->
        {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        });
        server.start();

        final var client = new BlueHatClient("localhost", port);
        final var request = new BlueHatClient.VmRequest(
            "1", "2", "RedHat 10.2", "test", "key"
        );

        assertThatThrownBy(() -> client.requestVm(request))
            .isInstanceOf(BlueHatException.class)
            .hasMessageContaining("VM request failed (HTTP 500)");
    }

    @Test
    void listVmsReturnsDetails()
    {
        server.createContext("/vm", exchange ->
        {
            if ("GET".equals(exchange.getRequestMethod()))
            {
                final var response = """
                    [
                      {
                        "vm_id": "2-3423076",
                        "description": "A VM for development",
                        "fqdn": "bluehat-vm-1785494151.dev.acme.com",
                        "state": "running",
                        "created": "2026-07-31 03:35:34",
                        "created_iso8601": "2026-07-31T03:35:34-0700"
                      },
                      {
                        "vm_id": "2-9999999",
                        "description": "Another VM",
                        "fqdn": "bluehat-vm-9999999.dev.acme.com",
                        "state": "stopped",
                        "created": "2026-07-30 01:00:00",
                        "created_iso8601": "2026-07-30T01:00:00-0700"
                      }
                    ]""";
                exchange.sendResponseHeaders(200, response.length());
                try (final var os = exchange.getResponseBody())
                {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();

        final var client = new BlueHatClient("localhost", port);
        final var vms = client.listVms();

        assertThat(vms).hasSize(2);
        assertThat(vms.getFirst().fqdn()).isEqualTo("bluehat-vm-1785494151.dev.acme.com");
        assertThat(vms.getFirst().state()).isEqualTo("running");
        assertThat(vms.getFirst().vmId()).isEqualTo("2-3423076");
        assertThat(vms.get(1).state()).isEqualTo("stopped");
    }

    @Test
    void listVmsThrowsOnHttpError()
    {
        server.createContext("/vm", exchange ->
        {
            exchange.sendResponseHeaders(503, 0);
            exchange.getResponseBody().close();
        });
        server.start();

        final var client = new BlueHatClient("localhost", port);

        assertThatThrownBy(() -> client.listVms())
            .isInstanceOf(BlueHatException.class)
            .hasMessageContaining("VM list failed (HTTP 503)");
    }

    @Test
    void destroyVmReturnsSuccess()
    {
        final var testFqdn = "bluehat-vm-12345.dev.acme.com";
        server.createContext("/vm/" + testFqdn, exchange ->
        {
            if ("DELETE".equals(exchange.getRequestMethod()))
            {
                final var response = """
                    {
                      "status": "success",
                      "details": "vm deletion initiated",
                      "request_id": "2-30931474"
                    }""";
                exchange.sendResponseHeaders(200, response.length());
                try (final var os = exchange.getResponseBody())
                {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();

        final var client = new BlueHatClient("localhost", port);
        final var response = client.destroyVm(testFqdn);

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.details()).isEqualTo("vm deletion initiated");
        assertThat(response.requestId()).isEqualTo("2-30931474");
    }

    @Test
    void destroyVmThrowsOnHttpError()
    {
        server.createContext("/vm/bad-fqdn", exchange ->
        {
            exchange.sendResponseHeaders(404, 0);
            exchange.getResponseBody().close();
        });
        server.start();

        final var client = new BlueHatClient("localhost", port);

        assertThatThrownBy(() -> client.destroyVm("bad-fqdn"))
            .isInstanceOf(BlueHatException.class)
            .hasMessageContaining("VM destroy failed (HTTP 404)");
    }

    @Test
    void requestVmSendsCorrectJsonFields()
    {
        final var capturedBody = new String[1];
        server.createContext("/vm", exchange ->
        {
            if ("POST".equals(exchange.getRequestMethod()))
            {
                capturedBody[0] = new String(
                    exchange.getRequestBody().readAllBytes()
                    , StandardCharsets.UTF_8
                );
                final var response = """
                    {"fqdn": "test.dev.acme.com"}""";
                exchange.sendResponseHeaders(200, response.length());
                try (final var os = exchange.getResponseBody())
                {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();

        final var client = new BlueHatClient("localhost", port);
        client.requestVm(new BlueHatClient.VmRequest(
            "8"
            , "16"
            , "RedHat 10.2"
            , "attimo VM created at 2026-08-14 10:00:00"
            , "ssh-ed25519 AAAA... attimo managed key"
        ));

        assertThat(capturedBody[0])
            .contains("\"cpu\":\"8\"")
            .contains("\"memory\":\"16\"")
            .contains("\"os\":\"RedHat 10.2\"")
            .contains("\"description\":\"attimo VM created at")
            .contains("\"ssh-public-key\":\"ssh-ed25519");
    }
}
