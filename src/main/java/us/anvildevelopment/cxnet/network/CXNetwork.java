/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network;

import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.Configuration;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.State;
import us.anvildevelopment.cxnet.edge.NetworkRecord;
import us.anvildevelopment.util.tools.database.annotations.MemoryOnly;
import us.anvildevelopment.util.tools.permissions.BasicPermissionContainer;

public class CXNetwork {
    public State networkState = State.ConnectNetworks;
    public Configuration configuration;
    public NetworkDictionary networkDictionary;
    /**
     * Zero Trust Mode
     * CAUTION After enabled zero trust mode, NMI can no longer make edits to network, network configuration, use NMI permissions, or get authority on blockchain consensus
     * This will essentially turn a centralized network that trust epoch into a fully decentralized P2P Mesh
     * What happens:
     * NMI executes .startZeroTrust();
     * 1. Network Seed is updated by NMI to show zT = true
     * (Note: NMI will still have its permissions in the permissions map but nodes will no longer treat it as NMI and practically block it from permissions actions, this is required to securely process the event)
     * 2. NMI Distributes ZeroTrustEvent, ZeroTrustEvent which includes new Seed - without blockchain data, is now distributed CXN and recorded to C1, Nodes will use new consensus with existing block height events/request
     * 3. Peers receive Zero Trust Event, apply new seed, switches to zT mode automatically due to seed application, then chains are resynced with Zero trust protocols
     *
     * This allows networks to be created and fine tuned centrally, then once stabilized, switch to a zero trust model
     */
    public boolean zT = false;
    /**
     * Administrative chain
     */
    public NetworkRecord c1;
    /**
     * Resources chain
     */
    public NetworkRecord c2;
    /**
     * Standard Events chain
     */
    public NetworkRecord c3;
    /**
     * Network permissions
     */
    public BasicPermissionContainer networkPermissions;

    /**
     * Consensus configuration for multi-peer block verification
     * These settings determine how the network reaches consensus on blockchain blocks
     * when EPOCH/NMI is unavailable or in zero trust mode
     */

    /**
     * Minimum number of peers required to reach block consensus
     * Default: 3 peers
     */
    public Integer consensusMinPeers = 3;

    /**
     * Minimum response rate required for consensus (0.0 to 1.0)
     * Default: 0.6 (60% of queried peers must respond)
     */
    public Double consensusMinResponseRate = 0.6;

    /**
     * Consensus threshold - percentage of peers that must agree (0.0 to 1.0)
     * Default: 0.67 (67% supermajority required)
     */
    public Double consensusThreshold = 0.67;

    /**
     * Timeout for block consensus requests in milliseconds
     * Default: 30000 (30 seconds)
     */
    public Long consensusTimeoutMs = 30000L;

    // NOTE: Whitelist (registered nodes) and blocklist (blocked nodes) are stored locally in DataContainer
    // They are NOT distributed in network seeds for security reasons
    // See ConnectX.dataContainer.networkRegisteredNodes and ConnectX.dataContainer.networkBlockedNodes

    /**
     * Check if a device is the current Network Master Identity (NMI)
     * @param deviceID Device UUID to check
     * @return true if device is the NMI (first backend in backendSet), false otherwise
     */
    private boolean isCurrentNMI(String deviceID) {
        return configuration != null &&
               configuration.backendSet != null &&
               !configuration.backendSet.isEmpty() &&
               configuration.backendSet.get(0).equals(deviceID);
    }

    public boolean checkChainPermission(String deviceID, String permission, Long chainID) {
        assert !permission.contains("-");
        assert !deviceID.contains("SYSTEM");

        // Zero Trust Mode: Block NMI from using permissions
        // After zT activation, NMI is treated as a regular node
        if (zT && isCurrentNMI(deviceID)) {
            LoggerFactory.getLogger(CXNetwork.class).info("[ZT-Blocked] NMI {} blocked from chain permission: {}-{}", deviceID.substring(0, 8), permission, chainID);
            return false;
        }

        String p = permission + "-"+chainID;
        return networkPermissions.allowed(deviceID, p);
    }

    /**
     * Whether a peer may perform an administrative action on THIS network.
     *
     * Authority comes from this network and only this network: its own NMI, its own
     * backendSet, or an explicit permission granted on it. CXNET's backendSet confers nothing
     * here, which matters because the scope check in NodeMesh.processNetworkInput authorises
     * against CXNET and could otherwise be mistaken for the authority governing an
     * administrative event aimed at a different network.
     *
     * Zero trust is honoured the way the other checks in this class honour it. Once zT is
     * active the NMI loses its standing entirely, including the backendSet membership it holds
     * by being the first backend, and must hold the permission like any other node. Other
     * backends are unaffected, matching the existing scope of zT.
     *
     * @param permission action-specific permission name, for example Permission.BlockNode
     */
    public boolean checkAdministrativeAuthority(String deviceID, String permission) {
        if (deviceID == null || permission == null) return false;

        if (!(zT && isCurrentNMI(deviceID)) && configuration != null) {
            if (deviceID.equals(configuration.nmiPub)) return true;
            if (configuration.backendSet != null && configuration.backendSet.contains(deviceID)) return true;
        }

        // Delegated administration, and the only route left to the NMI once zT is active.
        return checkNetworkPermission(deviceID, permission);
    }

    public boolean checkNetworkPermission(String deviceID, String permission) {
        assert !permission.contains("-");
        assert !deviceID.contains("SYSTEM");

        // Zero Trust Mode: Block NMI from using permissions
        if (zT && isCurrentNMI(deviceID)) {
            LoggerFactory.getLogger(CXNetwork.class).info("[ZT-Blocked] NMI {} blocked from network permission: {}", deviceID.substring(0, 8), permission);
            return false;
        }

        return networkPermissions.allowed(deviceID, permission);
    }

    public boolean checkGlobalPermission(String deviceID, String permission, ConnectX connectX) {
        // Note: Global permissions are managed by CXNET, not individual networks
        // Zero trust mode only affects network-level permissions, not global ones
        return connectX.checkGlobalPermission(deviceID, permission);
    }

    public Integer getVariableNetworkPermission(String deviceID, String permission) {
        // Zero Trust Mode: Block NMI from using permissions
        if (zT && isCurrentNMI(deviceID)) {
            LoggerFactory.getLogger(CXNetwork.class).info("[ZT-Blocked] NMI {} blocked from variable permission: {}", deviceID.substring(0, 8), permission);
            return null;
        }

        if (checkNetworkPermission(deviceID,permission)) {
            return networkPermissions.getEntryWeight(deviceID,permission);
        } else return null;
    }

    // ---------------------------------------------------------------------------------------
    // Broadcast and relay permissions
    // ---------------------------------------------------------------------------------------

    /**
     * Resolved permission cache, keyed "identity\u0000node".
     *
     * Resolution walks a handful of candidate keys per check and the relay path runs it on every
     * inbound broadcast, so results are memoised. Cleared wholesale on any governance change --
     * grant, revoke, seed application or zero-trust activation -- because a stale allow outlives
     * a revocation, which is the one direction that must never happen.
     */
    @MemoryOnly
    private java.util.concurrent.ConcurrentHashMap<String, Boolean> permissionCache;

    /**
     * Not final, and null-checked on every use: this class is deserialized when a network arrives
     * in a seed, and a field initializer is not something to rely on across every deserialization
     * path. v0.4.3 records a persisted object silently failing to load because Jackson picked up
     * members it should not have.
     */
    private java.util.concurrent.ConcurrentHashMap<String, Boolean> cache() {
        java.util.concurrent.ConcurrentHashMap<String, Boolean> c = permissionCache;
        if (c == null) {
            synchronized (this) {
                c = permissionCache;
                if (c == null) {
                    c = new java.util.concurrent.ConcurrentHashMap<>();
                    permissionCache = c;
                }
            }
        }
        return c;
    }

    /** Drop every memoised permission result. Call after any change to governance state. */
    public void invalidatePermissionCache() {
        java.util.concurrent.ConcurrentHashMap<String, Boolean> c = permissionCache;
        if (c != null) c.clear();
    }

    private boolean cachedResolve(String identity, String node) {
        if (identity == null || node == null) return false;
        String key = identity + '\u0000' + node;
        java.util.concurrent.ConcurrentHashMap<String, Boolean> c = cache();
        Boolean hit = c.get(key);
        if (hit != null) return hit;
        boolean verdict = us.anvildevelopment.cxnet.network.permissions.PermissionResolver
                .allowed(networkPermissions, identity, node);
        c.put(key, verdict);
        return verdict;
    }

    /** This network's scope string for permission nodes. */
    private String scopeName() {
        return configuration != null && configuration.netID != null ? configuration.netID : "CXNET";
    }

    /**
     * May this identity originate a broadcast of this event type into this network's scope?
     *
     * Checked against the event's verified ORIGIN, never its transmitter. Authorizing authorship
     * on the transmitter is the laundering bug the v0.6.2 administrative handlers were hardened
     * against: an attacker's event relayed by any granted backend would pass.
     */
    public boolean checkBroadcastPermission(String originID, String eventType) {
        if (originID == null) return false;
        if (zT && isCurrentNMI(originID)) return false;
        return cachedResolve(originID,
                us.anvildevelopment.cxnet.network.permissions.PermissionResolver
                        .node(scopeName(), "broadcast", eventType));
    }

    /**
     * May this identity forward a broadcast it received?
     *
     * Checked against the authenticated TRANSMITTER, and deliberately a different permission from
     * broadcast. One permission checked against one identity cannot work: against the origin, a
     * backend relaying a new peer's event is dropped; against the transmitter, propagation dies at
     * the second hop because the next relay holds no grant. Splitting them lets origination stay
     * closed while forwarding stays open to members, with no identity ever being rewritten.
     *
     * Safe to grant broadly: a relay can only forward something already authorized at its origin,
     * and the event dedup map bounds amplification. Revoking it for one identity is the lever for
     * killing a misbehaving relay.
     */
    public boolean checkRelayPermission(String transmitterID, String eventType) {
        if (transmitterID == null) return false;
        return cachedResolve(transmitterID,
                us.anvildevelopment.cxnet.network.permissions.PermissionResolver
                        .node(scopeName(), "relay", eventType));
    }

    public CXNetwork() {}

}
