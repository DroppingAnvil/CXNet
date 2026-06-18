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
    private Server appJettyServer;
    private OkHttpClient httpClient;
    private int serverPort = -1;

    // Pending HTML responses for the app servlet: sid -> queue of rendered HTML
    // The AppServlet blocks on this queue after firing an APP_REQUEST.
    // NodeMesh APP_RESPONSE handler delivers the rendered HTML here to unblock.
    private static final ConcurrentHashMap<String, LinkedBlockingQueue<String>> pendingAppHTML
            = new ConcurrentHashMap<>();

    private static final long APP_RESPONSE_TIMEOUT_MS = 5000;

    // Per-tab session state: sessionToken -> AppSession.
    // Each GET /app/{appID} creates a new session token returned as X-CXApp-Session header.
    // POST requests must supply the same header to identify their targetCXID.
    // This ensures multiple browser tabs for the same appID are fully isolated.
    // TODO: add session expiry for long-running deployments.
    private static final ConcurrentHashMap<String, AppSession> appSessions = new ConcurrentHashMap<>();

    private static class AppSession {
        final String appID;
        final String targetCXID;
        AppSession(String appID, String targetCXID) {
            this.appID       = appID;
            this.targetCXID  = targetCXID;
        }
    }

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

    /**
     * Start the internal CXApp HTTP server, bound exclusively to 127.0.0.1.
     * Only the local Chrome extension (or other localhost clients) can reach this server.
     * External traffic cannot reach a loopback-bound socket at the OS level.
     *
     * @param internalPort port for the internal app server (e.g. 8079)
     */
    public void startAppServer(int internalPort) throws Exception {
        appJettyServer = new Server();
        ServerConnector connector = new ServerConnector(appJettyServer);
        connector.setHost("127.0.0.1");
        connector.setPort(internalPort);
        appJettyServer.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new AppServlet()), "/app/*");

        appJettyServer.setHandler(context);
        appJettyServer.start();

        log.info("[CXApp] Internal app server started on 127.0.0.1:{}", internalPort);
        log.info("[CXApp]   App endpoint: http://127.0.0.1:{}/app/{{appID}}", internalPort);
    }

    /**
     * Deliver rendered HTML to a pending AppServlet request.
     * Called by the NodeMesh APP_RESPONSE handler after applyAndRender().
     *
     * @param sid  the event sid used to correlate request and response
     * @param html the rendered HTML to return to the browser
     */
    public static void deliverAppHTML(String sid, String html) {
        LinkedBlockingQueue<String> queue = pendingAppHTML.get(sid);
        if (queue != null) queue.offer(html);
    }

    @Override
    public void stopServer() {
        if (appJettyServer != null) {
            try { appJettyServer.stop(); } catch (Exception e) {
                log.error("Error stopping internal app server", e);
            }
        }
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
                log.debug("HTTP Bridge failed: {} {}", response.code(), response.message());
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
            log.debug("HTTP Bridge transmit error: {}", e.getMessage());
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
                int contentLength = req.getContentLength();
                if (contentLength > us.anvildevelopment.cxnet.network.nodemesh.NodeConfig.IO_MAX_INPUT) {
                    log.warn("[cxHTTP1] Oversized Content-Length ({} bytes), dropping", contentLength);
                    resp.sendError(413, "Payload too large");
                    return;
                }
                byte[] body = req.getInputStream().readNBytes(us.anvildevelopment.cxnet.network.nodemesh.NodeConfig.IO_MAX_INPUT + 1);
                if (body.length > us.anvildevelopment.cxnet.network.nodemesh.NodeConfig.IO_MAX_INPUT) {
                    log.warn("[cxHTTP1] Oversized body ({} bytes), dropping", body.length);
                    resp.sendError(413, "Payload too large");
                    return;
                }
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

    /**
     * Internal CXApp servlet. Only reachable on 127.0.0.1 (loopback-bound port).
     * A secondary address check is applied as defense-in-depth.
     *
     * Wire behavior: only field values cross the CX network. HTML is generated
     * locally by the registered CXAppClient using its template. The servlet returns
     * locally-rendered HTML to Chrome. No HTML ever travels over CX.
     *
     * GET  /app/{appID}?cxid={peerCXID}[&amp;addr={bridgeAddr}]
     *   Fires a REFRESH and returns fully rendered HTML.
     *   addr is optional. If provided, the peer is registered directly without PeerFind.
     *
     * POST /app/{appID}
     *   Body JSON: {"op":"INVOKE","target":"methodName","args":["arg0",...]}
     *   Fires the op and returns re-rendered HTML after fields are updated.
     */
    class AppServlet extends HttpServlet {

        private static final String LOOPBACK_V4 = "127.0.0.1";
        private static final String LOOPBACK_V6 = "::1";

        private static final String SESSION_HEADER = "X-CXApp-Session";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if (!isLoopback(req)) { resp.sendError(403, "Forbidden"); return; }

            String appID = extractAppID(req);
            if (appID == null) { resp.sendError(400, "Missing appID"); return; }

            String cxid = req.getParameter("cxid");
            String addr = req.getParameter("addr");
            if (cxid == null || cxid.isEmpty()) { resp.sendError(400, "Missing cxid"); return; }

            if (connectX.getAppClient(appID) == null) { resp.sendError(404, "Unknown app: " + appID); return; }

            if (addr != null && !addr.isEmpty()) registerPeerHint(cxid, addr);

            String sessionToken = java.util.UUID.randomUUID().toString();
            appSessions.put(sessionToken, new AppSession(appID, cxid));

            String html = fireAndWait(appID, cxid, "REFRESH", null, null);
            if (html == null) { resp.sendError(504, "CXApp server did not respond in time"); return; }

            resp.setHeader(SESSION_HEADER, sessionToken);
            writeHTML(resp, html);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if (!isLoopback(req)) { resp.sendError(403, "Forbidden"); return; }

            String sessionToken = req.getHeader(SESSION_HEADER);
            if (sessionToken == null || sessionToken.isEmpty()) {
                resp.sendError(401, "Missing " + SESSION_HEADER + " header. Open the app first.");
                return;
            }

            AppSession session = appSessions.get(sessionToken);
            if (session == null) { resp.sendError(401, "Unknown session. Open the app again."); return; }

            if (connectX.getAppClient(session.appID) == null) {
                resp.sendError(404, "Unknown app: " + session.appID);
                return;
            }

            byte[] body = req.getInputStream().readAllBytes();
            us.anvildevelopment.cxnet.app.CXAppRequest appReq;
            try {
                appReq = (us.anvildevelopment.cxnet.app.CXAppRequest)
                        ConnectX.deserialize("cxJSON1", new String(body, "UTF-8"),
                                us.anvildevelopment.cxnet.app.CXAppRequest.class);
            } catch (Exception e) {
                resp.sendError(400, "Invalid request body: " + e.getMessage());
                return;
            }

            String html = fireAndWait(session.appID, session.targetCXID, appReq.op, appReq.target, appReq.args);
            if (html == null) { resp.sendError(504, "CXApp server did not respond in time"); return; }

            resp.setHeader(SESSION_HEADER, sessionToken);
            writeHTML(resp, html);
        }

        private String fireAndWait(String appID, String targetCXID, String op, String target, String[] args) {
            String sid = java.util.UUID.randomUUID().toString();
            LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
            pendingAppHTML.put(sid, queue);
            try {
                us.anvildevelopment.cxnet.app.CXAppRequest appReq =
                        new us.anvildevelopment.cxnet.app.CXAppRequest(appID, op, target, args, true);
                String json = ConnectX.serialize("cxJSON1", appReq);
                connectX.buildEvent(us.anvildevelopment.cxnet.network.events.EventType.APP_REQUEST,
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .withSid(sid)
                        .toPeer(targetCXID)
                        .signData()
                        .queue();
                return queue.poll(APP_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error("[CXApp] AppServlet fireAndWait error: {}", e.getMessage());
                return null;
            } finally {
                pendingAppHTML.remove(sid);
            }
        }

        private void registerPeerHint(String cxid, String addr) {
            try {
                us.anvildevelopment.cxnet.network.nodemesh.Node hint =
                        new us.anvildevelopment.cxnet.network.nodemesh.Node();
                hint.cxID = cxid;
                hint.addr = addr;
                connectX.nodeMesh.peerDirectory.addNode(hint);
                log.info("[CXApp] Registered peer hint: {} -> {}", cxid, addr);
            } catch (Exception e) {
                log.warn("[CXApp] Could not register peer hint for {}: {}", cxid, e.getMessage());
            }
        }

        private void writeHTML(HttpServletResponse resp, String html) throws IOException {
            resp.setContentType("text/html;charset=UTF-8");
            resp.setStatus(200);
            resp.getOutputStream().write(html.getBytes("UTF-8"));
        }

        private String extractAppID(HttpServletRequest req) {
            String path = req.getPathInfo();
            if (path == null || path.length() < 2) return null;
            String id = path.startsWith("/") ? path.substring(1) : path;
            return id.isEmpty() ? null : id;
        }

        private boolean isLoopback(HttpServletRequest req) {
            String addr = req.getRemoteAddr();
            return LOOPBACK_V4.equals(addr) || LOOPBACK_V6.equals(addr);
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