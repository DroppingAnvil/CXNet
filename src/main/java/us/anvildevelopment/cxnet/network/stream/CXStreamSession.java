/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.stream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.api.CXStreamPlugin;
import us.anvildevelopment.cxnet.network.events.CXStream;
import us.anvildevelopment.cxnet.network.events.EventType;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages one active stream session between two CX peers.
 *
 * <p>Frame format (TCP):
 * {@code [4-byte big-endian length][ciphertext + 16-byte GCM tag]}
 *
 * <p>Frame format (WebSocket):
 * {@code [ciphertext + 16-byte GCM tag]} (WebSocket handles framing)
 *
 * <p>IV is derived per-chunk: {@code baseIV XOR counter} - never transmitted.
 * Send and receive counters are independent and start at 0.
 */
public class CXStreamSession {
    private static final Logger log = LoggerFactory.getLogger(CXStreamSession.class);

    /** 4-byte magic prefix identifying a stream connection on the main P2P port. */
    public static final byte[] STREAM_MAGIC = {'C', 'X', 'S', 'T'};

    public enum State { PENDING, OPEN, CLOSED }
    public enum TransportType { DIRECT_TCP, WEBSOCKET }

    public final String sessionID;
    public final String remoteCxID;
    public volatile State state = State.PENDING;
    public final TransportType transportType;

    private final CXStreamCipher cipher;
    public final int bufferSize;
    private final CXStreamPlugin plugin;

    /** Host and port of the initiator's main P2P socket, stored from the OPEN payload. */
    public String remoteTCPHost;
    public int remoteTCPPort = -1;

    // TCP transport
    private ServerSocket pendingServerSocket;
    private volatile Socket tcpSocket;
    private DataOutputStream tcpOut;
    private DataInputStream tcpIn;

    // WebSocket transport -- one of these is set depending on direction
    private volatile okhttp3.WebSocket wsOkClient;       // outbound: OkHttp client
    private volatile Session wsJettySession;              // inbound:  Jetty server session

    private final AtomicLong sendCounter = new AtomicLong(0);
    private final AtomicLong recvCounter = new AtomicLong(0);

    public CXStreamSession(String sessionID, String remoteCxID, CXStreamCipher cipher,
                           int bufferSize, CXStreamPlugin plugin, TransportType transportType) {
        this.sessionID = sessionID;
        this.remoteCxID = remoteCxID;
        this.cipher = cipher;
        this.bufferSize = bufferSize;
        this.plugin = plugin;
        this.transportType = transportType;
    }

    // -------------------------------------------------------------------------
    // Setup: TCP listener
    // -------------------------------------------------------------------------

    /**
     * Bind a ServerSocket on a free OS-assigned port.
     * Returns the allocated port for inclusion in the OPEN/ACCEPT event payload.
     */
    public int startListening() throws IOException {
        pendingServerSocket = new ServerSocket(0);
        return pendingServerSocket.getLocalPort();
    }

    /**
     * Spawn a daemon thread that blocks on ServerSocket.accept().
     * When the remote peer connects, opens the session and starts the receive loop.
     * Call this after sending the OPEN or ACCEPT event that advertises the port.
     */
    public void awaitInboundConnection() {
        Thread t = new Thread(() -> {
            try {
                tcpSocket = pendingServerSocket.accept();
                pendingServerSocket.close();
                pendingServerSocket = null;
                openTCPStreams(null, 0);
                transitionToOpen();
                startTCPReceiveLoop();
            } catch (Exception e) {
                if (state != State.CLOSED) {
                    log.error("[CXStream] {} await inbound failed", tag(), e);
                    handleClose();
                }
            }
        }, "cxstream-await-" + tag());
        t.setDaemon(true);
        t.start();
    }

    /**
     * Outbound TCP connect to remote peer's listening port.
     * Sends the CXST magic header first when {@code mainPortMux} is true
     * (i.e. connecting to the peer's main P2P port rather than a dedicated stream port).
     */
    public void connectTCP(String host, int port, boolean mainPortMux) {
        Thread t = new Thread(() -> {
            try {
                tcpSocket = new Socket(host, port);
                if (mainPortMux) sendStreamHeader(tcpSocket);
                openTCPStreams(null, 0);
                transitionToOpen();
                startTCPReceiveLoop();
            } catch (Exception e) {
                log.error("[CXStream] {} TCP connect to {}:{} failed", tag(), host, port, e);
                handleClose();
            }
        }, "cxstream-connect-" + tag());
        t.setDaemon(true);
        t.start();
    }

    /**
     * Attach a raw socket accepted by SocketWatcher after the CXST magic was detected.
     * {@code leadingData} contains the full initial buffer read by SocketWatcher;
     * {@code dataOffset} is the byte index where stream frame data starts (after the header).
     */
    public void attachSocket(Socket socket, byte[] leadingData, int dataOffset) {
        Thread t = new Thread(() -> {
            try {
                tcpSocket = socket;
                tcpSocket.setSoTimeout(0); // clear SocketWatcher's 400ms read timeout
                openTCPStreams(leadingData, dataOffset);
                transitionToOpen();
                startTCPReceiveLoop();
            } catch (Exception e) {
                log.error("[CXStream] {} attachSocket failed", tag(), e);
                handleClose();
            }
        }, "cxstream-attach-" + tag());
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Setup: WebSocket (bridge transport)
    // -------------------------------------------------------------------------

    /**
     * Connect outbound to the remote peer's WebSocket stream server using OkHttp.
     * The session ID is passed as a query parameter so the server can match it.
     */
    public void connectWebSocket(String wsBaseUrl, OkHttpClient okHttpClient) {
        Request request = new Request.Builder()
            .url(wsBaseUrl + "?session=" + sessionID)
            .build();
        okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(okhttp3.WebSocket ws, okhttp3.Response response) {
                wsOkClient = ws;
                transitionToOpen();
            }
            @Override
            public void onMessage(okhttp3.WebSocket ws, ByteString bytes) {
                handleIncomingWSFrame(bytes.toByteArray());
            }
            @Override
            public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
                handleClose();
            }
            @Override
            public void onFailure(okhttp3.WebSocket ws, Throwable t, okhttp3.Response response) {
                log.error("[CXStream] {} WebSocket client failure: {}", tag(), t.getMessage());
                handleClose();
            }
        });
    }

    /**
     * Attach an accepted Jetty server-side WebSocket session.
     * Called by {@code CXStreamWSEndpoint.onWebSocketConnect}.
     */
    public void attachWebSocket(Session jettySession) {
        this.wsJettySession = jettySession;
        transitionToOpen();
        log.info("[CXStream] {} OPEN via WebSocket (server-side)", tag());
    }

    // -------------------------------------------------------------------------
    // Data path
    // -------------------------------------------------------------------------

    /**
     * Encrypt and send one chunk of application data.
     *
     * <p>TCP frame: {@code [4-byte big-endian length][ciphertext+tag]}
     * <p>WebSocket frame: {@code [ciphertext+tag]} (WS framing handles boundaries)
     *
     * @param data plaintext; should not exceed the negotiated buffer size
     */
    public void write(byte[] data) throws Exception {
        if (state != State.OPEN) throw new IllegalStateException("Stream " + tag() + " is not open");
        long counter = sendCounter.getAndIncrement();
        byte[] encrypted = cipher.encrypt(data, counter);

        // Use the transport that is actually connected -- the declared transportType may
        // differ if the receiver downgraded from WebSocket to TCP after the session was created
        if (tcpOut != null) {
            synchronized (tcpOut) {
                tcpOut.writeInt(encrypted.length);
                tcpOut.write(encrypted);
                tcpOut.flush();
            }
        } else if (wsOkClient != null) {
            wsOkClient.send(ByteString.of(encrypted));
        } else if (wsJettySession != null && wsJettySession.isOpen()) {
            wsJettySession.getRemote().sendBytes(ByteBuffer.wrap(encrypted));
        } else {
            throw new IllegalStateException("No active transport for stream " + tag());
        }
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    /**
     * Gracefully close the session: sends a STREAM CLOSE CX event then tears down
     * the data channel.
     */
    public void close(ConnectX cx) {
        if (state == State.CLOSED) return;
        CXStream closePayload = new CXStream();
        closePayload.operation = CXStream.Operation.CLOSE;
        closePayload.streamID = sessionID;
        try {
            String json = ConnectX.serialize("cxJSON1", closePayload);
            cx.buildEvent(EventType.STREAM, json.getBytes(StandardCharsets.UTF_8))
                .toPeer(remoteCxID)
                    .addRecipient(remoteCxID)
                .encrypt()
                .queue();
        } catch (Exception e) {
            log.warn("[CXStream] {} failed to send CLOSE event: {}", tag(), e.getMessage());
        }
        handleClose();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Open DataOutputStream/DataInputStream over the TCP socket.
     * If {@code leadingData} is non-null and has bytes after {@code dataOffset},
     * those bytes are prepended to the input stream via SequenceInputStream so
     * the reception loop processes any data already read by SocketWatcher.
     */
    private void openTCPStreams(byte[] leadingData, int dataOffset) throws IOException {
        tcpOut = new DataOutputStream(new BufferedOutputStream(tcpSocket.getOutputStream(), bufferSize));
        InputStream rawIn = tcpSocket.getInputStream();
        if (leadingData != null && dataOffset < leadingData.length) {
            byte[] remainder = Arrays.copyOfRange(leadingData, dataOffset, leadingData.length);
            rawIn = new SequenceInputStream(new ByteArrayInputStream(remainder), rawIn);
        }
        tcpIn = new DataInputStream(new BufferedInputStream(rawIn, bufferSize));
    }

    private void transitionToOpen() {
        state = State.OPEN;
        String actualTransport = tcpSocket != null ? "TCP" : (wsOkClient != null || wsJettySession != null ? "WEBSOCKET" : "UNKNOWN");
        log.info("[CXStream] {} OPEN ({})", tag(), actualTransport);
    }

    private void startTCPReceiveLoop() {
        Thread t = new Thread(() -> {
            try {
                while (state == State.OPEN) {
                    int len = tcpIn.readInt();
                    if (len <= 0 || len > NodeConfig.streamMaxBufferSize + 32) {
                        log.warn("[CXStream] {} invalid chunk length {}", tag(), len);
                        break;
                    }
                    byte[] encrypted = new byte[len];
                    tcpIn.readFully(encrypted);
                    decryptAndDeliver(encrypted);
                }
            } catch (EOFException ignored) {
            } catch (Exception e) {
                if (state == State.OPEN) log.error("[CXStream] {} receive error", tag(), e);
            }
            handleClose();
        }, "cxstream-recv-" + tag());
        t.setDaemon(true);
        t.start();
    }

    /** Called by WebSocket callbacks on binary frame arrival. */
    public void handleIncomingWSFrame(byte[] frame) {
        decryptAndDeliver(frame);
    }

    private void decryptAndDeliver(byte[] encrypted) {
        try {
            long counter = recvCounter.getAndIncrement();
            byte[] plaintext = cipher.decrypt(encrypted, counter);
            plugin.onStreamData(this, plaintext);
        } catch (Exception e) {
            log.error("[CXStream] {} decryption failed (GCM auth tag mismatch or corrupted frame)", tag(), e);
        }
    }

    /**
     * Send the CXST protocol header on a TCP connection to the peer's main P2P port.
     * Format: {@code CXST (4 bytes) | idLen (1 byte) | sessionID bytes (idLen bytes)}
     */
    private void sendStreamHeader(Socket socket) throws IOException {
        byte[] idBytes = sessionID.getBytes(StandardCharsets.UTF_8);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.write(STREAM_MAGIC);
        out.writeByte(idBytes.length);
        out.write(idBytes);
        out.flush();
    }

    public void handleClose() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) {}
        try { if (pendingServerSocket != null) pendingServerSocket.close(); } catch (Exception ignored) {}
        try { if (wsOkClient != null) wsOkClient.close(1000, "closed"); } catch (Exception ignored) {}
        try { if (wsJettySession != null) wsJettySession.close(); } catch (Exception ignored) {}
        log.info("[CXStream] {} CLOSED", tag());
        plugin.onStreamClosed(this);
    }

    private String tag() {
        return sessionID != null && sessionID.length() >= 8 ? sessionID.substring(0, 8) : sessionID;
    }
}