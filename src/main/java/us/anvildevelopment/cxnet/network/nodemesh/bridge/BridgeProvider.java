/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh.bridge;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.CXPath;
import us.anvildevelopment.cxnet.network.events.NetworkContainer;

import java.util.List;

/**
 * BridgeProvider - Abstraction for CX network bridges
 *
 * Similar to EncryptionProvider and SerializationProvider, this provides
 * a pluggable system for different network transport bridges.
 *
 * Bridges enable CX to work over various protocols:
 * - cxHTTP1: HTTP/HTTPS tunneling for reverse proxy compatibility
 * - cxWS1: WebSocket for browser clients
 * - cxTOR1: Tor hidden services
 * - etc.
 *
 * Each bridge must handle the unique constraints of its protocol
 * (e.g., HTTP is request/response, not bidirectional like sockets)
 */
public interface BridgeProvider {

    /**
     * Get the bridge protocol identifier (e.g., "cxHTTP1", "cxWS1")
     * This is used in CXPath.bridge to route messages
     */
    String getBridgeProtocol();

    /**
     * Initialize the bridge with ConnectX instance
     * Called once during bridge registration
     */
    void initialize(ConnectX connectX);

    /**
     * CLIENT SIDE: Transmit an event through this bridge
     *
     * @param path CXPath containing bridge routing info (path.bridgeArg)
     * @param containerBytes Signed NetworkContainer bytes
     * @return Response NetworkContainers (for synchronous bridges like HTTP)
     *         Returns empty list for async bridges (sockets, websockets)
     * @throws Exception if transmission fails
     */
    List<NetworkContainer> transmitEvent(CXPath path, byte[] containerBytes) throws Exception;

    /**
     * SERVER SIDE: Start bridge server (if applicable)
     * For bridges that need to listen for incoming connections
     *
     * @param port Port to listen on
     * @throws Exception if server startup fails
     */
    void startServer(int port) throws Exception;

    /**
     * SERVER SIDE: Stop bridge server
     */
    void stopServer();

    /**
     * Check if this bridge supports bidirectional communication
     * - true: Bridge maintains persistent connection (sockets, websockets)
     * - false: Bridge is request/response only (HTTP) - requires sync responses
     */
    boolean isBidirectional();

    /**
     * Check if this bridge requires synchronous response handling
     * - true: Responses must be returned in same connection (HTTP)
     * - false: Responses can be sent asynchronously (sockets)
     */
    boolean requiresSyncResponses();

    /**
     * Validate whether a full peer address string is a well-formed address for this bridge.
     *
     * The address is in the format "protocol:arg" (e.g. "cxHTTP1:https://host/cx").
     * Each bridge implementation validates the arg portion against its own spec.
     * Malformed addresses must be rejected before they enter routing tables.
     *
     * Default implementation returns false — bridges must explicitly opt in.
     */
    default boolean isValidAddress(String addr) {
        return false;
    }
}