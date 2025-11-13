/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh;

/**
 * Transmission preferences for NetworkContainer routing.
 * Controls how messages are routed through the network.
 * Part of NetworkContainer - sent with every message.
 */
public class TransmitPref {
    /**
     * Direct-only transmission mode.
     * When true: Find the specific target node and send directly (no relaying/proxy).
     * Use for point-to-point communication when you know the target is reachable.
     * Default: false
     */
    public boolean directOnly = false;

    /**
     * Peer proxy mode - blockchain-based eventual delivery.
     * When true: Upload event to the blockchain (if permissions match) and distribute
     * to all peers. The event will eventually reach the target through peer propagation.
     * Use when target availability is uncertain or for guaranteed eventual delivery.
     * Default: false
     */
    public boolean peerProxy = false;

    /**
     * Peer broadcast mode - global cross-network transmission.
     * When true: Send message through every network (global CX messages).
     * Use for network-wide announcements or cross-network communication.
     * Default: true
     */
    public boolean peerBroad = true;

    /**
     * Specific proxy node ID.
     * When set: Route through this specific proxy node.
     * Use when you want to use a specific intermediary for routing.
     */
    public String proxy = null;

    /**
     * Bridge node ID for cross-network routing.
     * When set: Use this bridge node to route between different networks.
     * Use for inter-network communication.
     */
    public String bridge = null;

    public TransmitPref() {
        // Default: peerBroad = true (broadcast mode)
    }
}
