/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh.bridge.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.CXPath;
import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider;
import okhttp3.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * cxHTTP1 Bridge Provider
 *
 * Enables CX networking over HTTP/HTTPS for:
 * - Reverse proxy compatibility (CloudFlare, RProx, nginx, etc.)
 * - Firewall traversal
 * - NAT bypass
 *
 * Architecture:
 * - CLIENT: Uses HTTPS for encrypted transport to reverse proxy
 * - SERVER: Plain HTTP behind reverse proxy (TLS termination at proxy)
 * - Trust Model: RProx (AnvilDevelopment.US/rprox.html) handles CloudFlare + TLS
 *
 * Example:
 * Client → HTTPS → CloudFlare/RProx → HTTP → EPOCH NMI Server
 *   URL: https://CXNET.AnvilDevelopment.US/cx
 *
 * HTTP Constraint: Request/Response model (not bidirectional)
 * Solution: Synchronous response collection
 *
 * Flow:
 * 1. Client POSTs NetworkContainer to /cx endpoint via HTTPS
 * 2. RProx terminates TLS and forwards to HTTP server
 * 3. Server processes event through normal CX stack
 * 4. Server captures any response events generated for this sender
 * 5. Server returns responses in HTTP body
 * 6. RProx encrypts response and returns to client via HTTPS
 * 7. Client processes responses locally
 */
public class HTTPBridgeProvider implements BridgeProvider {

    private static final String PROTOCOL = "cxHTTP1";
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

    private ConnectX connectX;
    private HttpServer httpServer;
    private OkHttpClient httpClient;

    // Response queues for synchronous HTTP handling
    // Key: requestId (from NetworkEvent.iD), Value: responses for that request
    private static final ConcurrentHashMap<String, LinkedBlockingQueue<NetworkContainer>> responseQueues = new ConcurrentHashMap<>();

    @Override
    public String getBridgeProtocol() {
        return PROTOCOL;
    }

    @Override
    public void initialize(ConnectX connectX) {
        this.connectX = connectX;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    @Override
    public boolean isBidirectional() {
        return false; // HTTP is request/response only
    }

    @Override
    public boolean requiresSyncResponses() {
        return true; // Must collect and return responses in same HTTP request
    }

    /**
     * CLIENT SIDE: Send event via HTTPS POST and wait for sync responses
     * Client uses HTTPS - TLS termination handled by RProx/CloudFlare
     */
    @Override
    public List<NetworkContainer> transmitEvent(CXPath path, byte[] containerBytes) throws Exception {
        List<NetworkContainer> responses = new ArrayList<>();

        try {
            System.out.println("[HTTP Bridge Client] Sending " + containerBytes.length + " bytes to " + path.bridgeArg);

            // POST encrypted NetworkContainer to HTTPS endpoint
            // Example: https://CXNET.AnvilDevelopment.US/cx
            RequestBody body = RequestBody.create(containerBytes, OCTET_STREAM);
            Request request = new Request.Builder()
                .url(path.bridgeArg) // Must be HTTPS URL (TLS handled by RProx)
                .post(body)
                .build();

            Response response = httpClient.newCall(request).execute();
            System.out.println("[HTTP Bridge Client] Response: " + response.code() + " " + response.message());

            if (!response.isSuccessful()) {
                throw new IOException("HTTP Bridge failed: " + response.code() + " " + response.message());
            }

            // Parse response body as array of NetworkContainers
            // TODO: Response format - for now assume single or multiple containers
            byte[] responseBody = response.body().bytes();
            if (responseBody != null && responseBody.length > 0) {
                try {
                    // Deserialize response NetworkContainers
                    // This would be an array of signed NetworkContainers
                    // For simplicity, treat as single container for now
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ByteArrayInputStream bais = new ByteArrayInputStream(responseBody);

                    // Decrypt and deserialize
                    Object decrypted = connectX.encryptionProvider.decrypt(bais, baos);
                    String json = baos.toString("UTF-8");

                    // Skip if response is not JSON (e.g., "OK", error messages)
                    String trimmed = json.trim();
                    if (!trimmed.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
                        NetworkContainer nc = (NetworkContainer) ConnectX.deserialize("cxJSON1", json, NetworkContainer.class);
                        responses.add(nc);
                    } else {
                        System.out.println("[HTTP Bridge] Received non-JSON response (acknowledgment): " + trimmed);
                    }
                } catch (Exception parseEx) {
                    // Response parsing failed - likely an acknowledgment or error message
                    System.out.println("[HTTP Bridge] Response parse skipped (likely acknowledgment)");
                }
            }

            response.close();
        } catch (Exception e) {
            System.err.println("HTTP Bridge transmit error: " + e.getMessage());
            throw e;
        }

        return responses;
    }

    /**
     * SERVER SIDE: Start HTTP server for incoming CX messages
     * Server uses plain HTTP - TLS termination handled by RProx reverse proxy
     * IMPORTANT: This server should NOT be exposed directly to internet
     *            Must be behind RProx/CloudFlare for security
     */
    @Override
    public void startServer(int port) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);

        // Main CX endpoint - receives forwarded requests from RProx
        httpServer.createContext("/cx", new CXMessageHandler());

        // Health check endpoint - for monitoring/load balancers
        httpServer.createContext("/health", new HealthCheckHandler());

        // NOTE: Seed download uses SEED_REQUEST event via /cx endpoint for security
        // This ensures proper two-layer signature verification through CX protocol

        httpServer.setExecutor(null); // Use default executor
        httpServer.start();

        System.out.println("cxHTTP1 Bridge Server started on port " + port);
        System.out.println("  INTERNAL Endpoint: http://localhost:" + port + "/cx");
        System.out.println("  INTERNAL Health: http://localhost:" + port + "/health");
        System.out.println("  PUBLIC Endpoint: https://CXNET.AnvilDevelopment.US/cx (via RProx)");
        System.out.println("  ⚠️  WARNING: Server uses plain HTTP - MUST be behind RProx for security");
    }

    @Override
    public void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            System.out.println("cxHTTP1 Bridge Server stopped");
        }
    }

    /**
     * Register a response queue for a specific request
     * Called before processing an HTTP request
     */
    public static void registerResponseQueue(String requestId) {
        responseQueues.put(requestId, new LinkedBlockingQueue<>());
    }

    /**
     * Add a response to the queue for a specific request
     * Called when CX generates a response event
     */
    public static void queueResponse(String requestId, NetworkContainer response) {
        LinkedBlockingQueue<NetworkContainer> queue = responseQueues.get(requestId);
        if (queue != null) {
            queue.offer(response);
        }
    }

    /**
     * Collect all responses for a request and clean up
     */
    public static List<NetworkContainer> collectResponses(String requestId, long timeoutMs) {
        List<NetworkContainer> responses = new ArrayList<>();
        LinkedBlockingQueue<NetworkContainer> queue = responseQueues.get(requestId);

        if (queue != null) {
            try {
                // Wait a bit for responses to arrive
                NetworkContainer response = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
                while (response != null) {
                    responses.add(response);
                    response = queue.poll(100, TimeUnit.MILLISECONDS); // Check for more
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                responseQueues.remove(requestId);
            }
        }

        return responses;
    }

    /**
     * Handler for CX messages over HTTP
     */
    class CXMessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed".getBytes());
                return;
            }

            try {
                // Read encrypted NetworkContainer from request - ensure stream is fully read
                InputStream requestStream = exchange.getRequestBody();
                byte[] requestBody = requestStream.readAllBytes();
                requestStream.close(); // Close the request stream

                System.out.println("[HTTP Bridge Server] Received " + requestBody.length + " bytes");

                // Process event through normal CX stack
                // This handles all decryption, verification, and event processing
                connectX.nodeMesh.processNetworkInput(new ByteArrayInputStream(requestBody), null);

                System.out.println("[HTTP Bridge Server] Event processed successfully");

                // For now, just acknowledge receipt with empty response
                // TODO: Implement proper sync response collection
                // This requires modifying processNetworkInput or the event system
                // to capture responses for HTTP bridge requests
                sendResponse(exchange, 200, new byte[0]);

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, ("Error: " + e.getMessage()).getBytes());
            }
        }
    }

    /**
     * Health check handler
     */
    class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"status\":\"healthy\",\"bridge\":\"cxHTTP1\",\"identity\":\"" +
                (connectX.getOwnID() != null ? connectX.getOwnID() : "unknown") + "\"}";

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            sendResponse(exchange, 200, response.getBytes("UTF-8"));
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, byte[] response) throws IOException {
        exchange.sendResponseHeaders(statusCode, response.length);
        OutputStream os = exchange.getResponseBody();
        os.write(response);
        os.close();
    }
}