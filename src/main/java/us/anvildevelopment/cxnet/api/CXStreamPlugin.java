/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.events.CXStream;
import us.anvildevelopment.cxnet.network.events.EventType;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.stream.AESGCMStreamCipher;
import us.anvildevelopment.cxnet.network.stream.CXStreamManager;
import us.anvildevelopment.cxnet.network.stream.CXStreamSession;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Abstract plugin base for stream sessions.
 *
 * <p>Register it like any other plugin:
 * <pre>
 *   peer.addPlugin(new CXStreamPlugin() {
 *       public void onStreamRequest(CXStreamSession session, String senderCxID) {
 *           session.accept(connectX); // or reject via session.close(connectX)
 *       }
 *       public void onStreamData(CXStreamSession session, byte[] data) {
 *           // process video frame / audio chunk / etc.
 *       }
 *       public void onStreamClosed(CXStreamSession session) {}
 *   });
 * </pre>
 *
 * <p>To initiate a stream to a remote peer:
 * <pre>
 *   streamPlugin.openStream(targetCxID, "127.0.0.1");
 * </pre>
 *
 * <p>STREAM events are always sent E2E encrypted. The session key inside the
 * payload is protected by the PGP E2E layer -- never send STREAM events without
 * {@code .encrypt()}.
 */
public abstract class CXStreamPlugin extends CXPlugin {
    private static final Logger log = LoggerFactory.getLogger(CXStreamPlugin.class);

    protected ConnectX connectX;
    protected CXStreamManager streamManager;

    public CXStreamPlugin() {
        super(EventType.STREAM.name());
        this.streamCapable = true;
        this.type = CXStream.class;
        this.dataLevel = DataLevel.OBJECT;
    }

    /** Called by ConnectX.addPlugin() to wire the plugin into the framework. */
    public void initialize(ConnectX cx, CXStreamManager manager) {
        this.connectX = cx;
        this.streamManager = manager;
        manager.setPlugin(this);
    }

    // -------------------------------------------------------------------------
    // Initiate a stream
    // -------------------------------------------------------------------------

    /**
     * Open a stream session to the specified peer.
     *
     * <p>Generates a fresh AES-GCM-256 session key and base IV, builds a
     * {@link CXStream#operation OPEN} payload, sends it E2E-encrypted to the
     * target, and registers a PENDING session. The session transitions to OPEN
     * when the target sends ACCEPT and the data channel is established.
     *
     * @param targetCxID cxID of the peer to stream to
     * @param localHost  the host/IP the receiver should connect back to (e.g. "127.0.0.1").
     *                   The initiator already knows which address works because they are already
     *                   routing events to the target via that address.
     *                   Pass null for bridge-only transport.
     */
    public void openStream(String targetCxID, String localHost) {
        if (connectX == null) {
            log.error("[CXStreamPlugin] Not initialized -- call addPlugin() before openStream()");
            return;
        }
        if (localHost == null) {
            log.warn("[CXStreamPlugin] openStream() called with null localHost -- TCP mux will not work");
        }

        SecureRandom rng = new SecureRandom();
        byte[] sessionKey = new byte[32]; // AES-256
        byte[] baseIV     = new byte[12]; // GCM 96-bit nonce base
        rng.nextBytes(sessionKey);
        rng.nextBytes(baseIV);

        String sessionID = UUID.randomUUID().toString();
        int bufferSize   = NodeConfig.streamDefaultBufferSize;

        // Determine transport and connection details
        CXStream offer = new CXStream();
        offer.operation          = CXStream.Operation.OPEN;
        offer.streamID           = sessionID;
        offer.cipher             = AESGCMStreamCipher.CIPHER_NAME;
        offer.sessionKey         = sessionKey;
        offer.baseIV             = baseIV;
        offer.proposedBufferSize = bufferSize;
        offer.initiatorHost      = localHost;

        // Prefer direct TCP if streamMainPortMux is on or we can allocate a port
        offer.bridgeAddress      = streamManager.getLocalStreamBridgeAddress();

        if (NodeConfig.streamMainPortMux) {
            // Advertise the main P2P port -- target will connect via CXST mux
            offer.port               = connectX.listeningPort;
            offer.listenerIsInitiator = true;
        } else {
            // Allocate a dedicated port
            int port = streamManager.allocateFreePort();
            if (port > 0) {
                offer.port               = port;
                offer.listenerIsInitiator = true;
            } else {
                // No dedicated port -- target will listen, we connect after ACCEPT
                offer.port               = -1;
                offer.listenerIsInitiator = false;
            }
        }

        // Build pending session with the cipher we just generated
        CXStreamSession.TransportType transport =
            offer.bridgeAddress != null ? CXStreamSession.TransportType.WEBSOCKET
                                        : CXStreamSession.TransportType.DIRECT_TCP;

        try {
            var cipher = AESGCMStreamCipher.create(offer.cipher, sessionKey, baseIV);
            CXStreamSession session = new CXStreamSession(
                sessionID, targetCxID, cipher, bufferSize, this, transport);
            streamManager.registerSession(session);

            if (offer.listenerIsInitiator && transport == CXStreamSession.TransportType.DIRECT_TCP) {
                if (!NodeConfig.streamMainPortMux) {
                    // Dedicated port: start listening and wait for the target to connect
                    session.startListening();
                    session.awaitInboundConnection();
                }
                // streamMainPortMux: SocketWatcher handles inbound -- no server socket needed
            }

            // Send E2E-encrypted OPEN event
            String json = ConnectX.serialize("cxJSON1", offer);
            connectX.buildEvent(EventType.STREAM, json.getBytes(StandardCharsets.UTF_8))
                .toPeer(targetCxID)
                    .addRecipient(targetCxID)
                .encrypt()
                .queue();

            log.info("[CXStreamPlugin] STREAM OPEN queued to {} session={}", targetCxID.substring(0, 8), sessionID.substring(0, 8));

        } catch (Exception e) {
            log.error("[CXStreamPlugin] Failed to open stream to {}: {}", targetCxID, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Accept an incoming stream request
    // -------------------------------------------------------------------------

    /**
     * Accept a pending stream request. Call this from {@link #onStreamRequest}
     * to confirm the session and connect the data channel.
     *
     * <p>Sends a STREAM ACCEPT event back with the negotiated buffer size and
     * connection details, then opens the data channel.
     */
    public void acceptStream(CXStreamSession session) {
        if (session.state != CXStreamSession.State.PENDING) {
            log.warn("[CXStreamPlugin] acceptStream called on non-PENDING session {}", session.sessionID);
            return;
        }

        int negotiated = Math.max(NodeConfig.streamMinBufferSize,
            Math.min(NodeConfig.streamMaxBufferSize, session.bufferSize));

        CXStream accept = new CXStream();
        accept.operation          = CXStream.Operation.ACCEPT;
        accept.streamID           = session.sessionID;
        accept.negotiatedBufferSize = negotiated;

        // Use WebSocket only if the initiator requested it AND our bridge is confirmed reachable.
        // If the initiator asked for TCP (no bridgeAddress in their OPEN), respect that -- unless
        // streamBridgeOnly is set, in which case we never expose a direct IP.
        String localStreamAddr = streamManager.getLocalStreamBridgeAddress();
        boolean bridgeReady = localStreamAddr != null
                && (session.transportType == CXStreamSession.TransportType.WEBSOCKET || NodeConfig.streamBridgeOnly)
                && streamManager.isLocalStreamBridgeReachable();

        if (bridgeReady) {
            // Bridge confirmed reachable: advertise WebSocket
            accept.bridgeAddress       = localStreamAddr;
            accept.listenerIsInitiator = false;
        } else if (NodeConfig.streamBridgeOnly) {
            // Bridge required but not available -- cannot accept this stream
            log.warn("[CXStreamPlugin] streamBridgeOnly is set but bridge is unreachable -- rejecting stream from {}",
                session.remoteCxID.substring(0, 8));
            session.handleClose();
            return;
        } else {
            // TCP: initiator asked for it, or our bridge is down/unreachable
            if (NodeConfig.streamMainPortMux) {
                accept.listenerIsInitiator = true;
                accept.port               = connectX.listeningPort;
            } else {
                try {
                    int port = session.startListening();
                    session.awaitInboundConnection();
                    accept.port               = port;
                    accept.listenerIsInitiator = false;
                } catch (Exception e) {
                    log.error("[CXStreamPlugin] Failed to start listener for accept: {}", e.getMessage());
                    return;
                }
            }
        }

        try {
            String json = ConnectX.serialize("cxJSON1", accept);
            connectX.buildEvent(EventType.STREAM, json.getBytes(StandardCharsets.UTF_8))
                .toPeer(session.remoteCxID)
                    .addRecipient(session.remoteCxID)
                .encrypt()
                .queue();

            log.info("[CXStreamPlugin] STREAM ACCEPT sent for session {}", session.sessionID.substring(0, 8));
        } catch (Exception e) {
            log.error("[CXStreamPlugin] Failed to send ACCEPT: {}", e.getMessage());
            return;
        }

        // For direct TCP mux: we (target) connect to the initiator's main port using the CXST header.
        // The session key and address were provided by the initiator in the OPEN payload.
        if (!bridgeReady && NodeConfig.streamMainPortMux) {
            if (session.remoteTCPHost != null && session.remoteTCPPort > 0) {
                session.connectTCP(session.remoteTCPHost, session.remoteTCPPort, true);
            } else {
                log.error("[CXStreamPlugin] Cannot connect stream to {} - initiatorHost or port missing in OPEN payload",
                    session.remoteCxID.substring(0, 8));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Plugin dispatch
    // -------------------------------------------------------------------------

    @Override
    public final boolean handleEvent(Object data, String senderCxID) {
        if (!(data instanceof CXStream)) return false;
        streamManager.handleStreamEvent((CXStream) data, senderCxID);
        return true;
    }

    // -------------------------------------------------------------------------
    // Callbacks - implement these in your subclass
    // -------------------------------------------------------------------------

    /**
     * Called when a remote peer requests a new stream session.
     * Call {@link #acceptStream(CXStreamSession)} to accept, or
     * {@link CXStreamSession#close(ConnectX)} to reject.
     *
     * @param session   the pending session (PENDING state)
     * @param senderCxID origin cxID of the requesting peer
     */
    public abstract void onStreamRequest(CXStreamSession session, String senderCxID);

    /**
     * Called when a decrypted data chunk arrives at an open stream session.
     *
     * @param session the session the data arrived on
     * @param data    decrypted plaintext chunk
     */
    public abstract void onStreamData(CXStreamSession session, byte[] data);

    /**
     * Called when a stream session is closed (by either side).
     *
     * @param session the now-closed session
     */
    public abstract void onStreamClosed(CXStreamSession session);
}