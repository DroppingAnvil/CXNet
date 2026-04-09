/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh.bridge.http;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.*;
import okio.ByteString;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.CXPath;
import us.anvildevelopment.cxnet.network.events.NetworkContainer;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.BridgeProvider;
import us.anvildevelopment.cxnet.network.stream.CXStreamSession;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * cxHTTP1 Bridge Provider (Jetty 11)
 *
 * Serves both the CX event endpoint and the stream WebSocket endpoint on the
 * same port so nodes behind a single-port firewall can use both features.
 *
 * Endpoints:
 *   POST /cx          -- CX event delivery (existing protocol)
 *   GET  /health      -- Health check
 *   WS   /cxstream    -- Stream data channel (query param: session=<sessionID>)
 *
 * Trust Model: plain HTTP/WS behind RProx/CloudFlare for TLS termination.
 */
public class HTTPBridgeProvider implements BridgeProvider {
    private static final Logger log = LoggerFactory.getLogger(HTTPBridgeProvider.class);

    private static final String PROTOCOL = "cxHTTP1";
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

    private ConnectX connectX;
    private Server jettyServer;
    private OkHttpClient httpClient;
    private int serverPort = -1;

    // Response queues for synchronous HTTP handling (request ID -> response queue)
    private static final ConcurrentHashMap<String, LinkedBlockingQueue<NetworkContainer>> responseQueues
        = new ConcurrentHashMap<>();

    @Override
    public String getBridgeProtocol() { return PROTOCOL; }

    @Override
    public boolean isStreamCapable() { return true; }

    @Override
    public void initialize(ConnectX connectX) {
        this.connectX = connectX;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build();
    }

    @Override
    public boolean isBidirectional() { return false; }

    @Override
    public boolean requiresSyncResponses() { return true; }

    @Override
    public boolean probeHealth(String targetAddress) {
        return probeHealthInternal(targetAddress, null);
    }

    /**
     * Probe health and verify the response identity matches {@code expectedIdentity}.
     * Use this to confirm the bridge actually routes to THIS node and not some other server.
     */
    public boolean probeHealthWithIdentity(String targetAddress, String expectedIdentity) {
        return probeHealthInternal(targetAddress, expectedIdentity);
    }

    private boolean probeHealthInternal(String targetAddress, String expectedIdentity) {
        if (targetAddress == null || !targetAddress.startsWith(PROTOCOL + ":")) return false;
        String url = targetAddress.substring(PROTOCOL.length() + 1);
        if (url.endsWith("/cx")) {
            url = url.substring(0, url.length() - 3) + "/health";
        } else if (url.endsWith("/")) {
            url = url + "health";
        } else {
            url = url + "/health";
        }
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) return false;
                if (expectedIdentity != null) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    return body.contains("\"identity\":\"" + expectedIdentity + "\"");
                }
                return true;
            }
        } catch (Exception e) {
            log.debug("[cxHTTP1] Health probe failed for {}: {}", targetAddress, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isValidAddress(String addr) {
        if (addr == null || addr.isEmpty()) return false;
        if (!addr.startsWith(PROTOCOL + ":")) return false;
        String url = addr.substring(PROTOCOL.length() + 1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        return url.length() > 8;
    }

    /**
     * Returns the WebSocket stream URL for this node's bridge, derived from
     * the HTTP bridge address by replacing http(s) with ws(s) and path with /cxstream.
     * Returns null if the server is not running.
     */
    @Override
    public String getStreamAddress() {
        if (serverPort <= 0) return null;
        // Derive from the node's own bridge address if available
        String self = connectX.getSelf() != null ? connectX.getSelf().addr : null;
        if (self != null && self.startsWith("cxHTTP1:")) {
            String url = self.substring("cxHTTP1:".length());
            // CX traffic is already encrypted -- always use plain ws://, no need for wss://
            // Strip the http(s):// scheme and rebuild with ws:// to avoid false matches
            // when the hostname starts with "cx" (e.g. cx1.example.com)
            String hostAndPath = url.replaceFirst("^https?://", "");
            int pathIdx = hostAndPath.indexOf("/cx");
            String base = pathIdx >= 0 ? hostAndPath.substring(0, pathIdx) : hostAndPath;
            return "ws://" + base + "/cxstream";
        }
        return "ws://localhost:" + serverPort + "/cxstream";
    }

    // -------------------------------------------------------------------------
    // Server
    // -------------------------------------------------------------------------

    @Override
    public void startServer(int port) throws Exception {
        this.serverPort = port;

        jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        connector.setHost("0.0.0.0");
        connector.setPort(port);
        jettyServer.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");

        // CX event endpoint
        context.addServlet(new ServletHolder(new CXServlet()), "/cx");
        // Health check endpoint
        context.addServlet(new ServletHolder(new HealthServlet()), "/health");

        // WebSocket stream endpoint on same port
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setMaxBinaryMessageSize(1024 * 1024 + 64); // max frame + tag overhead
            wsContainer.addMapping("/cxstream", (upgradeRequest, upgradeResponse) ->
                new CXStreamWSEndpoint(connectX));
        });

        jettyServer.setHandler(context);
        jettyServer.start();

        log.info("cxHTTP1 Bridge Server (Jetty) started on port {}", port);
        log.info("  CX endpoint:     http://0.0.0.0:{}/cx", port);
        log.info("  Stream endpoint: ws://0.0.0.0:{}/cxstream", port);
    }

    @Override
    public void stopServer() {
        if (jettyServer != null) {
            try {
                jettyServer.stop();
                log.info("cxHTTP1 Bridge Server stopped");
            } catch (Exception e) {
                log.error("Error stopping Jetty server", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Client
    // -------------------------------------------------------------------------

    @Override
    public List<NetworkContainer> transmitEvent(CXPath path, byte[] containerBytes) throws Exception {
        List<NetworkContainer> responses = new ArrayList<>();
        RequestBody body = RequestBody.create(containerBytes, OCTET_STREAM);
        Request request = new Request.Builder()
            .url(path.bridgeArg)
            .post(body)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP Bridge failed: " + response.code() + " " + response.message());
            }
            byte[] responseBody = response.body() != null ? response.body().bytes() : new byte[0];
            if (responseBody.length > 0) {
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    connectX.encryptionProvider.decrypt(new ByteArrayInputStream(responseBody), baos);
                    String json = baos.toString("UTF-8");
                    String trimmed = json.trim();
                    if (!trimmed.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
                        NetworkContainer nc = (NetworkContainer) ConnectX.deserialize(
                            "cxJSON1", json, NetworkContainer.class);
                        responses.add(nc);
                    }
                } catch (Exception ignored) {
                    // Acknowledgment or non-JSON response -- not an error
                }
            }
        } catch (Exception e) {
            log.error("HTTP Bridge transmit error: {}", e.getMessage());
            throw e;
        }
        return responses;
    }

    // -------------------------------------------------------------------------
    // Response queue (sync HTTP bridge)
    // -------------------------------------------------------------------------

    public static void registerResponseQueue(String requestId) {
        responseQueues.put(requestId, new LinkedBlockingQueue<>());
    }

    public static void queueResponse(String requestId, NetworkContainer response) {
        LinkedBlockingQueue<NetworkContainer> queue = responseQueues.get(requestId);
        if (queue != null) queue.offer(response);
    }

    public static List<NetworkContainer> collectResponses(String requestId, long timeoutMs) {
        List<NetworkContainer> responses = new ArrayList<>();
        LinkedBlockingQueue<NetworkContainer> queue = responseQueues.get(requestId);
        if (queue != null) {
            try {
                NetworkContainer r = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
                while (r != null) {
                    responses.add(r);
                    r = queue.poll(100, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                responseQueues.remove(requestId);
            }
        }
        return responses;
    }

    // -------------------------------------------------------------------------
    // HTTP servlets (Jetty / Jakarta)
    // -------------------------------------------------------------------------

    class CXServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            try {
                byte[] body = req.getInputStream().readAllBytes();
                connectX.nodeMesh.processNetworkInput(new ByteArrayInputStream(body), null);
                resp.setStatus(200);
            } catch (Exception e) {
                log.error("[cxHTTP1] Error processing event", e);
                resp.sendError(500, e.getMessage());
            }
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.sendError(405, "Method not allowed, use CX Protocol");
        }
    }

    class HealthServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setStatus(200);
            String json = "{\"status\":\"healthy\",\"bridge\":\"cxHTTP1\",\"identity\":\""
                + (connectX.getOwnID() != null ? connectX.getOwnID() : "unknown") + "\"}";
            resp.getOutputStream().write(json.getBytes("UTF-8"));
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket stream endpoint (server-side, Jetty)
    // -------------------------------------------------------------------------

    static class CXStreamWSEndpoint implements WebSocketListener {
        private static final Logger wsLog = LoggerFactory.getLogger(CXStreamWSEndpoint.class);

        private final ConnectX connectX;
        private Session jettySession;
        private String streamSessionID;

        CXStreamWSEndpoint(ConnectX connectX) {
            this.connectX = connectX;
        }

        @Override
        public void onWebSocketConnect(Session session) {
            this.jettySession = session;
            String query = session.getUpgradeRequest().getQueryString();
            if (query != null && query.startsWith("session=")) {
                streamSessionID = query.substring("session=".length());
            }
            if (streamSessionID == null || streamSessionID.isEmpty()) {
                wsLog.warn("[cxHTTP1/WS] Stream connection without session ID -- closing");
                session.close(1008, "Missing session ID");
                return;
            }
            if (connectX.streamManager != null) {
                CXStreamSession cxSession = connectX.streamManager.getSession(streamSessionID);
                if (cxSession != null) {
                    cxSession.attachWebSocket(session);
                } else {
                    wsLog.warn("[cxHTTP1/WS] No pending session for ID {}", streamSessionID.substring(0, 8));
                    session.close(1008, "Unknown session");
                }
            }
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
            if (streamSessionID == null || connectX.streamManager == null) return;
            CXStreamSession cxSession = connectX.streamManager.getSession(streamSessionID);
            if (cxSession != null) {
                byte[] frame = new byte[len];
                System.arraycopy(payload, offset, frame, 0, len);
                cxSession.handleIncomingWSFrame(frame);
            }
        }

        @Override
        public void onWebSocketText(String message) {}

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            if (streamSessionID != null && connectX.streamManager != null) {
                CXStreamSession cxSession = connectX.streamManager.getSession(streamSessionID);
                if (cxSession != null) cxSession.handleClose();
            }
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            wsLog.error("[cxHTTP1/WS] Error in stream session {}", streamSessionID, cause);
        }
    }
}