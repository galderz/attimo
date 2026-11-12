package org.mendrugo.attimo.bluehat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class BlueHatDummyServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong VM_COUNTER = new AtomicLong();

    private final HttpServer server;
    private final int port;
    private final Map<String, VmRecord> vms = new ConcurrentHashMap<>();

    record VmRecord(
            String vmId,
            String description,
            String fqdn,
            String state,
            String created,
            String createdIso8601,
            String cpu,
            String memory,
            String os) {}

    public BlueHatDummyServer() throws IOException {
        this(0);
    }

    public BlueHatDummyServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        this.port = server.getAddress().getPort();
        setupHandlers();
    }

    public int port() {
        return port;
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public int activeVmCount() {
        return (int) vms.values()
                        .stream()
                        .filter(vm -> "running".equals(vm.state()))
                        .count();
    }

    private void setupHandlers() {
        server.createContext("/vm", exchange -> {
            try {
                final String method = exchange.getRequestMethod();
                final String path = exchange.getRequestURI().getPath();

                switch (method) {
                    case "POST" -> handleCreateVm(exchange);
                    case "GET" -> handleListVms(exchange);
                    case "DELETE" -> {
                        final String fqdn = path.substring("/vm/".length());
                        handleDestroyVm(exchange, fqdn);
                    }
                    default -> {
                        exchange.sendResponseHeaders(405, 0);
                        exchange.getResponseBody().close();
                    }
                }
            } catch (Exception e) {
                final String error = "{\"error\": \"" + e.getMessage() + "\"}";
                final byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleCreateVm(HttpExchange exchange) throws IOException {
        final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        final Map request = JSON.readValue(body, Map.class);
        final String cpu = (String) request.getOrDefault("cpu", "1");
        final String memory = (String) request.getOrDefault("memory", "2");
        final String os = (String) request.getOrDefault("os", "RedHat 10.2");
        final String description = (String) request.getOrDefault("description", "");

        final long vmNumber = VM_COUNTER.incrementAndGet();
        final String fqdn = "bluehat-vm-" + vmNumber + ".dev.acme.com";
        final String vmId = "2-" + (3000000 + vmNumber);
        final OffsetDateTime now = OffsetDateTime.now();

        final VmRecord record = new VmRecord(vmId, description, fqdn, "running", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), cpu, memory, os);

        vms.put(fqdn, record);

        final String response = "{\"fqdn\": \"" + fqdn + "\"}";
        final byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private void handleListVms(HttpExchange exchange) throws IOException {
        final List<Map<String, String>> vmList = vms.values()
                .stream()
                .map(
                        vm -> Map.of(
                                "vm_id",
                                vm.vmId(),
                                "description",
                                vm.description(),
                                "fqdn",
                                vm.fqdn(),
                                "state",
                                vm.state(),
                                "created",
                                vm.created(),
                                "created_iso8601",
                                vm.createdIso8601()))
                .toList();

        final String response = JSON.writeValueAsString(vmList);
        final byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private void handleDestroyVm(HttpExchange exchange, String fqdn) throws IOException {
        final VmRecord vm = vms.get(fqdn);

        if (vm == null) {
            final String notFound = "{\"status\": \"error\", \"details\": \"VM not found\"}";
            final byte[] bytes = notFound.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(bytes);
            }
            return;
        }

        vms.put(
                fqdn,
                new VmRecord(
                        vm.vmId(),
                        vm.description(),
                        vm.fqdn(),
                        "deleted",
                        vm.created(),
                        vm.createdIso8601(),
                        vm.cpu(),
                        vm.memory(),
                        vm.os()));

        final var
                response = """
            {"status": "success", "details": "vm deletion initiated", "request_id": "%s"}"""
                                .formatted(vm.vmId());
        final byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }
}
