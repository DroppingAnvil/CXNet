/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

/**
 * CXHELLO payload data structure for peer discovery
 * Used for LAN peer discovery and CXHELLO/CXHELLO_RESPONSE exchange
 */
public class CXHello {
    /**
     * Peer ID of the sender
     */
    public String peerID;

    /**
     * Listening port of the sender (for LAN direct connections)
     */
    public int port;
    /**
     * Peer provided address for connections, can be different from what is in Node
     */
    public String address;

    /**
     * Signed Node blob from origin (PGP-signed Node object)
     * Used for .cxi persistence and relay
     */
    public byte[] signedNode;

    /**
     * Default constructor for Jackson deserialization
     */
    public CXHello() {}

    /**
     * Constructor with all fields
     */
    public CXHello(String peerID, int port, byte[] signedNode) {
        this.peerID = peerID;
        this.port = port;
        this.signedNode = signedNode;
        this.address = null; // Default: derive from socket
    }

    /**
     * Constructor with all fields including custom address
     */
    public CXHello(String peerID, int port, byte[] signedNode, String address) {
        this.peerID = peerID;
        this.port = port;
        this.signedNode = signedNode;
        this.address = address;
    }
}