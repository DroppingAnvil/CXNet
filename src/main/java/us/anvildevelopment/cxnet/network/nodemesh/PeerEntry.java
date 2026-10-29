/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import us.anvildevelopment.util.tools.database.annotations.MemoryOnly;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything this node knows about one peer, in one place.
 *
 * Replaces what used to be spread across three objects: the Node values held in
 * PeerDirectory's seen/lan/peerCache maps, and the socket addresses held separately in
 * DataContainer.localPeerAddresses. Keeping the addresses next to the peer they belong to is
 * what lets getAllAddresses() stop joining two stores on every send.
 *
 * Threading: PeerDirectory is read and written from pooled IOThreads, OutConnectionController
 * and RetryProcessor, not just the EventProcessor. Mutable scalars are volatile and the address
 * list is synchronized. Only eviction takes a lock, in PeerDirectory.
 */
public class PeerEntry implements Serializable {

    /**
     * The peer's identity. May be null for an entry created from an address sighting before the
     * identity itself has been imported.
     */
    public volatile Node node;

    /**
     * Known addresses in priority order, as DataContainer.localPeerAddresses held them:
     * advertised/CXHELLO addresses first, passively discovered receiving sockets appended.
     */
    public final List<String> addresses = Collections.synchronizedList(new ArrayList<>());

    /**
     * Last passively-discovered receiving socket, tracked so a new one replaces it rather than
     * accumulating. Without this a busy peer collects dozens of dead ephemeral ports.
     */
    public volatile String lastReceivingSocket;

    /**
     * Consecutive failed contact attempts. Drives the availability term of the priscore and is
     * reset to 0 on any successful contact, so a recovered node is not left buried.
     */
    public volatile int retries;

    /** Last time anything was heard from this peer. Eviction tiebreak. */
    public volatile long lastSeen;

    /** Last time this node successfully reached the peer. */
    public volatile long lastContact;

    /**
     * Whether the peer's advertised tcp4 address or bridge passed the nonce challenge.
     *
     * Not durable. Node.addr is @MemoryOnly so advertised addresses do not survive a restart,
     * which means a persisted "verified" flag would outlive the address it refers to. Re-verify
     * after restart instead of trusting it.
     */
    @MemoryOnly
    public volatile boolean endpointVerified;

    /** When the endpoint was last probed. Drives lazy re-verification. */
    @MemoryOnly
    public volatile long endpointCheckedAt;

    /** The specific address that passed verification, so a re-advertisement can invalidate it. */
    @MemoryOnly
    public volatile String verifiedEndpoint;

    /**
     * Peer sits on one of this node's actual interface subnets. Local means cheap to reach, not
     * trusted: a local peer still goes through the nonce challenge.
     */
    @MemoryOnly
    public volatile boolean localSubnet;

    /**
     * Explicitly linked by the user. Never evicted, never aged out, cleared only by the user.
     * This is not local network discovery, it is the user binding their own devices together.
     */
    public volatile boolean userLinked;

    /**
     * Held as a priority backend in BackendMap. Eviction-exempt, otherwise a backend could be
     * dropped from the directory while the backend map still points at it.
     */
    @MemoryOnly
    public volatile boolean backendPinned;

    /** Loaded from a .cxi/.cxic on disk rather than met live. */
    public volatile boolean diskCached;

    public PeerEntry() {}

    public PeerEntry(Node node) {
        this.node = node;
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * Priority score, 0 to 6, computed on read. At 100 entries a linear pass beats maintaining a
     * sorted structure, and computing on read means a retry or a probe result takes effect
     * immediately without touching an index.
     *
     * Availability term is 3/(retries+1): fresh 3, then 1.5, 1, 0.75, never reaching zero, so a
     * long-dead peer still outranks nothing at all. Float division, deliberately: integer
     * division would collapse 1.5 to 1 and flatten the top of the curve.
     *
     * Endpoint term is tiered rather than additive. A verified advertised endpoint or bridge is
     * worth +2; a local-subnet or user-linked peer is worth +3, so local and linked peers always
     * outrank a good remote endpoint and the mesh prefers them. A local peer that also has a
     * verified endpoint scores 3 for the term, not 5 -- these are two tiers of one property, not
     * two properties.
     */
    public float priscore() {
        float endpoint = Math.max(endpointVerified ? 2f : 0f,
                                  (localSubnet || userLinked) ? 3f : 0f);
        return 3f / (retries + 1) + endpoint;
    }

    /**
     * Whether this entry survives eviction regardless of score. User-linked peers persist until
     * the user removes them; pinned backends are owned by BackendMap.
     */
    public boolean evictionExempt() {
        return userLinked || backendPinned;
    }

    /** A successful contact: clear the retry count so the peer climbs back up the ranking. */
    public void recordSuccess() {
        retries = 0;
        long now = System.currentTimeMillis();
        lastContact = now;
        lastSeen = now;
    }

    /** A failed contact attempt against the peer itself, not merely its advertised endpoint. */
    public void recordFailure() {
        if (retries < Integer.MAX_VALUE) retries++;
    }

    /**
     * The advertised endpoint failed but the peer is still reachable another way. Drops the +2
     * without incrementing retries -- endpoint health is tracked separately from node health, so
     * a bad bridge does not make a reachable peer look dead.
     */
    public void recordEndpointFailure() {
        endpointVerified = false;
        verifiedEndpoint = null;
        endpointCheckedAt = System.currentTimeMillis();
    }

    public void recordEndpointVerified(String address) {
        endpointVerified = true;
        verifiedEndpoint = address;
        endpointCheckedAt = System.currentTimeMillis();
    }
}
