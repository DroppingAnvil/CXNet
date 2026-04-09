/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.stream;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.api.CXStreamPlugin;
import us.anvildevelopment.cxnet.network.events.CXStream;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.BridgeProvider;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.http.HTTPBridgeProvider;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of all {@link CXStreamSession}s for one {@link ConnectX} instance.
 *
 * <p>Handles incoming STREAM event routing (OPEN / ACCEPT / CLOSE), session registry,
 * port allocation, and bridge stream address resolution.
 */
public class CXStreamManager {
    private static final Logger log = LoggerFactory.getLogger(CXStreamManager.class);

    private final ConnectX connectX;
    public final ConcurrentHashMap<String, CXStreamSession> sessions = new ConcurrentHashMap<>();
    private CXStreamPlugin plugin;

    // Shared OkHttpClient for WebSocket outbound connections
    private final OkHttpClient wsClient = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout for persistent streams
        .build();

    public CXStreamManager(ConnectX connectX) {
        this.connectX = connectX;
    }

    public void setPlugin(CXStreamPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerSession(CXStreamSession session) {
        sessions.put(session.sessionID, session);
    }

    public CXStreamSession getSession(String sessionID) {
        return sessions.get(sessionID);
    }

    public void removeSession(String sessionID) {
        sessions.remove(sessionID);
    }

    // -------------------------------------------------------------------------
    // Incoming STREAM event routing
    // -------------------------------------------------------------------------

    /**
     * Route a decoded STREAM event payload to the correct handler.
     * Called from {@link CXStreamPlugin#handleEvent} after E2E decryption and deserialization.
     */
    public void handleStreamEvent(CXStream stream, String senderCxID) {
        if (stream == null || stream.operation == null || stream.streamID == null) {
            log.warn("[CXStreamManager] Malformed STREAM event from {}", senderCxID);
            return;
        }
        switch (stream.operation) {
            case OPEN:   handleOpen(stream, senderCxID);   break;
            case ACCEPT: handleAccept(stream, senderCxID); break;
            case CLOSE:  handleClose(stream, senderCxID);  break;
        }
    }

    // -------------------------------------------------------------------------
    // OPEN -- remote peer requesting a stream session
    // -------------------------------------------------------------------------

    private void handleOpen(CXStream stream, String senderCxID) {
        if (plugin == null) {
            log.warn("[CXStreamManager] STREAM OPEN from {} -- no CXStreamPlugin registered", senderCxID);
            return;
        }
        int bufferSize = clampBufferSize(stream.proposedBufferSize);
        CXStreamSession.TransportType transport = resolveTransport(stream);

        CXStreamCipher cipher;
        try {
            cipher = AESGCMStreamCipher.create(
                stream.cipher != null ? stream.cipher : AESGCMStreamCipher.CIPHER_NAME,
                stream.sessionKey, stream.baseIV);
        } catch (Exception e) {
            log.error("[CXStreamManager] STREAM OPEN from {} -- invalid cipher material: {}", senderCxID, e.getMessage());
            return;
        }

        CXStreamSession session = new CXStreamSession(
            stream.streamID, senderCxID, cipher, bufferSize, plugin, transport);
        session.remoteTCPHost = stream.initiatorHost;
        session.remoteTCPPort = stream.port;
        registerSession(session);

        // If the initiator is the listener, we (target) must connect after accepting
        // The session is PENDING until the plugin calls session.accept() via CXStreamPlugin
        plugin.onStreamRequest(session, senderCxID);
    }

    // -------------------------------------------------------------------------
    // ACCEPT -- remote peer accepted our outgoing stream request
    // -------------------------------------------------------------------------

    private void handleAccept(CXStream stream, String senderCxID) {
        CXStreamSession session = sessions.get(stream.streamID);
        if (session == null) {
            log.warn("[CXStreamManager] STREAM ACCEPT for unknown session {} from {}",
                tag(stream.streamID), senderCxID);
            return;
        }
        log.info("[CXStreamManager] STREAM ACCEPT for {} from {}", tag(stream.streamID), senderCxID);

        // Transport is decided by the receiver -- drive entirely off the ACCEPT payload
        if (stream.bridgeAddress != null) {
            session.connectWebSocket(stream.bridgeAddress, wsClient);
        } else if (!stream.listenerIsInitiator && stream.port > 0) {
            String host = resolveHostForPeer(senderCxID);
            if (host != null) {
                session.connectTCP(host, stream.port, false);
            } else {
                log.error("[CXStreamManager] Cannot resolve address for {} to connect stream", senderCxID);
            }
        }
        // listenerIsInitiator=true: our awaitInboundConnection() already running
    }

    // -------------------------------------------------------------------------
    // CLOSE -- remote peer closed the session
    // -------------------------------------------------------------------------

    private void handleClose(CXStream stream, String senderCxID) {
        CXStreamSession session = sessions.remove(stream.streamID);
        if (session != null) {
            log.info("[CXStreamManager] STREAM CLOSE for {} from {}", tag(stream.streamID), senderCxID);
            session.handleClose();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers used by CXStreamPlugin.openStream()
    // -------------------------------------------------------------------------

    /**
     * Allocate a free local port using OS port-0 binding.
     * Returns -1 if allocation fails.
     */
    public int allocateFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            log.warn("[CXStreamManager] Port allocation failed");
            return -1;
        }
    }

    /**
     * Return the WebSocket stream URL advertised by the first stream-capable bridge
     * registered on this node, or null if none.
     */
    public String getLocalStreamBridgeAddress() {
        for (BridgeProvider bridge : connectX.getBridgeProviders()) {
            if (bridge.isStreamCapable()) {
                String addr = bridge.getStreamAddress();
                if (addr != null) return addr;
            }
        }
        return null;
    }

    /**
     * Returns true if our own HTTP bridge is reachable AND routes to this node.
     * Probes the health endpoint and checks the identity in the response -- this
     * rules out cases where the public URL resolves to a different server entirely
     * (e.g. test nodes using cx*.anvildevelopment.us as a fake public address).
     */
    public boolean isLocalStreamBridgeReachable() {
        String selfAddr = connectX.getSelf() != null ? connectX.getSelf().addr : null;
        String ownID = connectX.getOwnID();
        if (selfAddr == null || ownID == null) return false;
        for (BridgeProvider bridge : connectX.getBridgeProviders()) {
            if (bridge.isStreamCapable() && bridge instanceof HTTPBridgeProvider) {
                HTTPBridgeProvider http = (HTTPBridgeProvider) bridge;
                return http.probeHealthWithIdentity(selfAddr, ownID);
            }
        }
        return false;
    }

    public OkHttpClient getWsClient() { return wsClient; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CXStreamSession.TransportType resolveTransport(CXStream stream) {
        if (stream.bridgeAddress != null
                && (stream.bridgeAddress.startsWith("ws://")
                    || stream.bridgeAddress.startsWith("wss://"))) {
            return CXStreamSession.TransportType.WEBSOCKET;
        }
        return CXStreamSession.TransportType.DIRECT_TCP;
    }

    private int clampBufferSize(int proposed) {
        if (proposed <= 0) return NodeConfig.streamDefaultBufferSize;
        return Math.max(NodeConfig.streamMinBufferSize,
                        Math.min(NodeConfig.streamMaxBufferSize, proposed));
    }

    private String resolveHostForPeer(String cxID) {
        try {
            var node = connectX.nodeMesh.peerDirectory.lookup(cxID, false, true);
            if (node != null && node.addr != null) {
                String addr = node.addr;
                // addr may be "host:port" or "cxHTTP1:https://..." -- extract just the host
                if (!addr.contains("://")) {
                    int colon = addr.lastIndexOf(':');
                    if (colon > 0) return addr.substring(0, colon);
                }
            }
        } catch (Exception e) {
            log.warn("[CXStreamManager] Address lookup failed for {}: {}", cxID, e.getMessage());
        }
        return null;
    }

    private String tag(String id) {
        return id != null && id.length() >= 8 ? id.substring(0, 8) : id;
    }
}