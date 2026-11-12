package org.mendrugo.attimo.bluehat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class BlueHatClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final HttpClient httpClient;

    public BlueHatClient(String hostName) {
        this(hostName, BlueHat.API_PORT);
    }

    public BlueHatClient(String hostName, int port) {
        this.baseUrl = "http://" + hostName + ":" + port;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    public VmResponse requestVm(VmRequest request) {
        try {
            final String body = JSON.writeValueAsString(request);
            final HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/vm"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(body))
                    .timeout(TIMEOUT.multipliedBy(10))
                    .build();

            final HttpResponse<String> response = httpClient.send(httpRequest, BodyHandlers.ofString());

            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new BlueHatException("VM request failed (HTTP " + response.statusCode() + "): " + response.body());
            }

            return JSON.readValue(response.body(), VmResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BlueHatException("VM request interrupted", e);
        } catch (IOException e) {
            throw new BlueHatException("Failed to request VM: " + e.getMessage(), e);
        }
    }

    public List<VmDetails> listVms() {
        try {
            final HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/vm"))
                    .GET()
                    .timeout(TIMEOUT)
                    .build();

            final HttpResponse<String> response = httpClient.send(httpRequest, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BlueHatException("VM list failed (HTTP " + response.statusCode() + "): " + response.body());
            }

            return JSON.readValue(response.body(), new TypeReference<List<VmDetails>>() {});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BlueHatException("List VMs interrupted", e);
        } catch (IOException e) {
            throw new BlueHatException("Failed to list VMs: " + e.getMessage(), e);
        }
    }

    public DestroyResponse destroyVm(String fqdn) {
        try {
            final HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/vm/" + fqdn))
                    .DELETE()
                    .timeout(TIMEOUT)
                    .build();

            final HttpResponse<String> response = httpClient.send(httpRequest, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BlueHatException("VM destroy failed (HTTP " + response.statusCode() + "): " + response.body());
            }

            return JSON.readValue(response.body(), DestroyResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BlueHatException("Destroy VM interrupted", e);
        } catch (IOException e) {
            throw new BlueHatException("Failed to destroy VM: " + e.getMessage(), e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmRequest(@JsonProperty("cpu") String cpu, @JsonProperty("memory") String memory, @JsonProperty("os") String os,
                            @JsonProperty("description") String description, @JsonProperty("ssh-public-key") String sshPublicKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmResponse(@JsonProperty("fqdn") String fqdn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VmDetails(@JsonProperty("vm_id") String vmId, @JsonProperty("description") String description, @JsonProperty("fqdn") String fqdn,
                            @JsonProperty("state") String state, @JsonProperty("created") String created, @JsonProperty("created_iso8601") String createdIso8601) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DestroyResponse(@JsonProperty("status") String status, @JsonProperty("details") String details, @JsonProperty("request_id") String requestId) {}
}
