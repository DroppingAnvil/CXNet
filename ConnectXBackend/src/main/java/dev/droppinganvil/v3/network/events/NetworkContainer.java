/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.events;

import dev.droppinganvil.v3.network.CXPath;
import dev.droppinganvil.v3.network.nodemesh.TransmitPref;

import java.io.Serializable;

/**
 * This data should not be E2E it must be P2P to work effectively
 *
 * CRYPTOGRAPHIC ARCHITECTURE NOTES:
 * - iD: Transmitter ID (changes with each relay hop)
 * - oD: Original creator ID (preserved through relays for permission checks)
 * - e: Signed NetworkEvent bytes (signature by original sender, preserved through relays)
 *
 * When relaying:
 * 1. New NetworkContainer created with iD = relay node's ID
 * 2. oD preserved from incoming container
 * 3. e (signed event bytes) preserved to maintain original signature
 * 4. NetworkContainer itself is re-signed by relay node
 */
public class NetworkContainer implements Serializable {
    public byte[] e;

    public CXPath p;
    public String se = "cxJSON1";
    /**
     * Higher security mode - Not implemented, do not use
     */
    public boolean s = false;
    /**
     * Transmitter ID - the node who sent this container to us (changes on each relay hop)
     * Will be null if s = true
     */
    public String iD;
    /**
     * Original sender ID - the node who created the event (preserved through relays)
     * Used for permission checks and authentication
     */
    public String oD;
    public TransmitPref tP;
    public Double v;
    public String tID;

    /**
     * Time To Live (TTL) - Remaining relay hops for this event
     * Decremented by 1 on each relay. When reaches 0, event is dropped (not relayed).
     * Prevents infinite loops and controls broadcast propagation scope.
     *
     * Value comes from network's Configuration.defaultTTL when event is created.
     */
    public Integer ttl;

}
