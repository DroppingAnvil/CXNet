/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network;

import us.anvildevelopment.cxnet.Configuration;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.State;
import us.anvildevelopment.cxnet.edge.NetworkRecord;
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
            LoggerFactory.getLogger(CXNetwork.class).info("[ZT-Blocked] NMI " + deviceID.substring(0, 8) + " blocked from chain permission: " + permission + "-" + chainID);
            return false;
        }

        String p = permission + "-"+chainID;
        return networkPermissions.allowed(deviceID, p);
    }

    public boolean checkNetworkPermission(String deviceID, String permission) {
        assert !permission.contains("-");
        assert !deviceID.contains("SYSTEM");

        // Zero Trust Mode: Block NMI from using permissions
        if (zT && isCurrentNMI(deviceID)) {
            LoggerFactory.getLogger(CXNetwork.class).info("[ZT-Blocked] NMI " + deviceID.substring(0, 8) + " blocked from network permission: " + permission);
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
            System.out.println("[ZT-Blocked] NMI " + deviceID.substring(0, 8) + " blocked from variable permission: " + permission);
            return null;
        }

        if (checkNetworkPermission(deviceID,permission)) {
            return networkPermissions.getEntryWeight(deviceID,permission);
        } else return null;
    }

    public CXNetwork() {}

}
