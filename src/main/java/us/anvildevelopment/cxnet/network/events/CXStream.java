/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

import java.io.Serializable;

/**
 * Payload for {@link EventType#STREAM} events.
 *
 * <p>A single class covers all three lifecycle operations:
 * <ul>
 *   <li>{@link Operation#OPEN} - initiator proposes a stream session</li>
 *   <li>{@link Operation#ACCEPT} - target confirms and provides connection details</li>
 *   <li>{@link Operation#CLOSE} - either side tears down the session</li>
 * </ul>
 *
 * <p>STREAM events MUST be sent E2E encrypted:
 * <pre>
 *   connectX.buildEvent(EventType.STREAM, json.getBytes())
 *       .toPeer(targetCxID)
 *       .encrypt(targetCxID)
 *       .queue();
 * </pre>
 *
 * The E2E encryption protects {@link #sessionKey} in transit -- do NOT send
 * STREAM events without {@code .encrypt()}.
 *
 * <p>The data channel itself is a separate TCP socket or WebSocket connection.
 * CX protocol is used only for orchestration and identity. All stream data is
 * encrypted with the negotiated {@link #cipher} using counter-based IV derivation.
 */
public class CXStream implements Serializable {

    public enum Operation { OPEN, ACCEPT, CLOSE }

    /** Lifecycle operation this payload represents. */
    public Operation operation;

    /** UUID identifying this stream session across both sides. */
    public String streamID;

    // --- Key material (OPEN only, protected by E2E event encryption) ---

    /** Cipher identifier. Default: {@code "AES-GCM-256"}. Pluggable. */
    public String cipher;

    /** Raw session key bytes. For AES-GCM-256: 32 bytes. */
    public byte[] sessionKey;

    /** 12-byte base IV. Per-chunk IV = baseIV XOR (counter in last 8 bytes). */
    public byte[] baseIV;

    // --- Buffer size negotiation ---

    /** OPEN: initiator's preferred buffer/chunk size in bytes. */
    public int proposedBufferSize;

    /** ACCEPT: receiver's negotiated chunk size after clamping to NodeConfig min/max. */
    public int negotiatedBufferSize;

    // --- Connection details ---

    /**
     * OPEN: if {@link #listenerIsInitiator} is true, the port the initiator is
     * listening on for an inbound TCP connection. -1 means bridge-only.
     *
     * ACCEPT: if {@link #listenerIsInitiator} is false, the port the target is
     * listening on.
     */
    public int port;

    /**
     * WebSocket bridge URL for the stream data channel.
     * Format: {@code ws://host:port/cxstream} or {@code wss://host:port/cxstream}.
     * Null means use direct TCP only.
     *
     * OPEN: initiator's bridge stream URL (if they have one).
     * ACCEPT: target's bridge stream URL (if they have one and the initiator should connect to it).
     */
    public String bridgeAddress;

    /**
     * True: initiator is the TCP listener; target must connect to initiator's {@link #port}.
     * False: target is the TCP listener; initiator must connect to the port in the ACCEPT payload.
     */
    public boolean listenerIsInitiator;

    /**
     * OPEN only: the host/IP the receiver should connect to for direct TCP
     * (e.g. {@code "127.0.0.1"} or {@code "192.168.1.5"}).
     * Provided by the initiator -- they already know their own routable address.
     * Null means bridge-only or caller did not provide an address.
     */
    public String initiatorHost;

    public CXStream() {}
}