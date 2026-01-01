/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network;

import dev.droppinganvil.v3.Configuration;
import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.State;
import dev.droppinganvil.v3.edge.NetworkRecord;
import dev.droppinganvil.v3.network.events.NetworkEvent;
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

    // NOTE: Whitelist (registered nodes) and blocklist (blocked nodes) are stored locally in DataContainer
    // They are NOT distributed in network seeds for security reasons
    // See ConnectX.dataContainer.networkRegisteredNodes and ConnectX.dataContainer.networkBlockedNodes

    public boolean checkChainPermission(String deviceID, String permission, Long chainID) {
        assert !permission.contains("-");
        assert !deviceID.contains("SYSTEM");
        String p = permission + "-"+chainID;
        return networkPermissions.allowed(deviceID, p);
    }

    public boolean checkNetworkPermission(String deviceID, String permission) {
        assert !permission.contains("-");
        assert !deviceID.contains("SYSTEM");
        return networkPermissions.allowed(deviceID, permission);
    }

    public boolean checkGlobalPermission(String deviceID, String permission) {
        return ConnectX.checkGlobalPermission(deviceID, permission);
    }

    public Integer getVariableNetworkPermission(String deviceID, String permission) {
        if (checkNetworkPermission(deviceID,permission)) {
            return networkPermissions.getEntryWeight(deviceID,permission);
        } else return null;
    }

    public CXNetwork() {}

}
