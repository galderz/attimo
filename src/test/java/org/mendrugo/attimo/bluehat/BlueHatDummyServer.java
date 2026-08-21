package org.mendrugo.attimo.bluehat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A lightweight dummy Blue Hat API server for integration testing.
 * Simulates the Blue Hat cloud proxy endpoints:
 * <ul>
 *   <li>POST /vm — create a VM, returns fqdn</li>
 *   <li>GET /vm — list all VMs</li>
 *   <li>DELETE /vm/{fqdn} — destroy a VM</li>
 * </ul>
 */
public class BlueHatDummyServer implements AutoCloseable
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong VM_COUNTER = new AtomicLong();

    private final HttpServer server;
    private final int port;
    private final Map<String, VmRecord> vms = new ConcurrentHashMap<>();

    record VmRecord(
        String vmId
        , String description
        , String fqdn
        , String state
        , String created
        , String createdIso8601
        , String cpu
        , String memory
        , String os
    )
    {}

    public BlueHatDummyServer() throws IOException
    {
        this(0); // random port
    }

    public BlueHatDummyServer(final int port) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        this.port = server.getAddress().getPort();
        setupHandlers();
    }

    public int port()
    {
        return port;
    }

    public void start()
    {
        server.start();
    }

    @Override
    public void close()
    {
        server.stop(0);
    }

    /**
     * Get the number of active (non-destroyed) VMs.
     */
    public int activeVmCount()
    {
        return (int)vms.values().stream()
            .filter(vm -> "running".equals(vm.state()))
            .count();
    }

    private void setupHandlers()
    {
        server.createContext("/vm", exchange ->
        {
            try
            {
                final var method = exchange.getRequestMethod();
                final var path = exchange.getRequestURI().getPath();

                switch (method)
                {
                    case "POST" -> handleCreateVm(exchange);
                    case "GET" -> handleListVms(exchange);
                    case "DELETE" ->
                    {
                        // Extract fqdn from path: /vm/{fqdn}
                        final var fqdn = path.substring("/vm/".length());
                        handleDestroyVm(exchange, fqdn);
                    }
                    default ->
                    {
                        exchange.sendResponseHeaders(405, 0);
                        exchange.getResponseBody().close();
                    }
                }
            }
            catch (final Exception e)
            {
                final var error = "{\"error\": \"" + e.getMessage() + "\"}";
                final var bytes = error.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                try (final var os = exchange.getResponseBody())
                {
                    os.write(bytes);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleCreateVm(
        final com.sun.net.httpserver.HttpExchange exchange
    ) throws IOException
    {
        final var body = new String(
            exchange.getRequestBody().readAllBytes()
            , StandardCharsets.UTF_8
        );

        final var request = JSON.readValue(body, Map.class);
        final var cpu = (String)request.getOrDefault("cpu", "1");
        final var memory = (String)request.getOrDefault("memory", "2");
        final var os = (String)request.getOrDefault("os", "RedHat 10.2");
        final var description = (String)request.getOrDefault("description", "");

        final var vmNumber = VM_COUNTER.incrementAndGet();
        final var fqdn = "bluehat-vm-" + vmNumber + ".dev.acme.com";
        final var vmId = "2-" + (3000000 + vmNumber);
        final var now = OffsetDateTime.now();

        final var record = new VmRecord(
            vmId
            , description
            , fqdn
            , "running"
            , now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            , now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            , cpu
            , memory
            , os
        );

        vms.put(fqdn, record);

        final var response = "{\"fqdn\": \"" + fqdn + "\"}";
        final var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (final var responseBody = exchange.getResponseBody())
        {
            responseBody.write(bytes);
        }
    }

    private void handleListVms(
        final com.sun.net.httpserver.HttpExchange exchange
    ) throws IOException
    {
        final var vmList = vms.values().stream()
            .map(vm -> Map.of(
                "vm_id", vm.vmId()
                , "description", vm.description()
                , "fqdn", vm.fqdn()
                , "state", vm.state()
                , "created", vm.created()
                , "created_iso8601", vm.createdIso8601()
            ))
            .toList();

        final var response = JSON.writeValueAsString(vmList);
        final var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (final var responseBody = exchange.getResponseBody())
        {
            responseBody.write(bytes);
        }
    }

    private void handleDestroyVm(
        final com.sun.net.httpserver.HttpExchange exchange
        , final String fqdn
    ) throws IOException
    {
        final var vm = vms.get(fqdn);

        if (vm == null)
        {
            final var notFound = "{\"status\": \"error\", \"details\": \"VM not found\"}";
            final var bytes = notFound.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            try (final var responseBody = exchange.getResponseBody())
            {
                responseBody.write(bytes);
            }
            return;
        }

        // Mark as deleted
        vms.put(fqdn, new VmRecord(
            vm.vmId()
            , vm.description()
            , vm.fqdn()
            , "deleted"
            , vm.created()
            , vm.createdIso8601()
            , vm.cpu()
            , vm.memory()
            , vm.os()
        ));

        final var response = """
            {"status": "success", "details": "vm deletion initiated", "request_id": "%s"}"""
            .formatted(vm.vmId());
        final var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (final var responseBody = exchange.getResponseBody())
        {
            responseBody.write(bytes);
        }
    }
}
