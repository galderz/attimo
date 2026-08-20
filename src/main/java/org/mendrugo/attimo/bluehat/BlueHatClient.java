package org.mendrugo.attimo.bluehat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP client for the Blue Hat cloud proxy API.
 * Handles VM request, list, and destroy operations.
 */
public class BlueHatClient
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final HttpClient httpClient;

    public BlueHatClient(final String hostName)
    {
        this(hostName, BlueHat.API_PORT);
    }

    public BlueHatClient(final String hostName, final int port)
    {
        this.baseUrl = "http://" + hostName + ":" + port;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    }

    /**
     * Request a new VM from Blue Hat cloud.
     *
     * @param request the VM request parameters
     * @return the response containing the FQDN of the provisioned VM
     * @throws BlueHatException if the request fails
     */
    public VmResponse requestVm(final VmRequest request)
    {
        try
        {
            final var body = JSON.writeValueAsString(request);
            final var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/vm"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                // Creating a VM can take considerably more,
                // so use a larger timeout compared to other requests.
                .timeout(TIMEOUT.multipliedBy(10))
                .build();

            final var response = httpClient.send(
                httpRequest
                , HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200 && response.statusCode() != 201)
            {
                throw new BlueHatException(
                    "VM request failed (HTTP " + response.statusCode() + "): " + response.body()
                );
            }

            return JSON.readValue(response.body(), VmResponse.class);
        }
        catch (final IOException | InterruptedException e)
        {
            if (e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }

            throw new BlueHatException("Failed to request VM: " + e.getMessage(), e);
        }
    }

    /**
     * List all VMs for the current user.
     *
     * @return list of VM details
     * @throws BlueHatException if the request fails
     */
    public List<VmDetails> listVms()
    {
        try
        {
            final var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/vm"))
                .GET()
                .timeout(TIMEOUT)
                .build();

            final var response = httpClient.send(
                httpRequest
                , HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200)
            {
                throw new BlueHatException(
                    "VM list failed (HTTP " + response.statusCode() + "): " + response.body()
                );
            }

            return JSON.readValue(response.body(), new TypeReference<List<VmDetails>>() {});
        }
        catch (final IOException | InterruptedException e)
        {
            if (e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }

            throw new BlueHatException("Failed to list VMs: " + e.getMessage(), e);
        }
    }

    /**
     * Destroy a VM by its FQDN.
     *
     * @param fqdn the fully qualified domain name of the VM to destroy
     * @return the destroy response
     * @throws BlueHatException if the request fails
     */
    public DestroyResponse destroyVm(final String fqdn)
    {
        try
        {
            final var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/vm/" + fqdn))
                .DELETE()
                .timeout(TIMEOUT)
                .build();

            final var response = httpClient.send(
                httpRequest
                , HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200)
            {
                throw new BlueHatException(
                    "VM destroy failed (HTTP " + response.statusCode() + "): " + response.body()
                );
            }

            return JSON.readValue(response.body(), DestroyResponse.class);
        }
        catch (final IOException | InterruptedException e)
        {
            if (e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }

            throw new BlueHatException("Failed to destroy VM: " + e.getMessage(), e);
        }
    }

    // --- Request/Response records ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmRequest(
        @JsonProperty("cpu") String cpu
        , @JsonProperty("memory") String memory
        , @JsonProperty("os") String os
        , @JsonProperty("description") String description
        , @JsonProperty("ssh-public-key") String sshPublicKey
    )
    {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmResponse(
        @JsonProperty("fqdn") String fqdn
    )
    {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmDetails(
        @JsonProperty("vm_id") String vmId
        , @JsonProperty("description") String description
        , @JsonProperty("fqdn") String fqdn
        , @JsonProperty("state") String state
        , @JsonProperty("created") String created
        , @JsonProperty("created_iso8601") String createdIso8601
    )
    {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DestroyResponse(
        @JsonProperty("status") String status
        , @JsonProperty("details") String details
        , @JsonProperty("request_id") String requestId
    )
    {}
}
