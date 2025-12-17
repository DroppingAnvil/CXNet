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
     * Blocked nodes for this network
     * Populated by reading BLOCK_NODE events from c1 (Admin) chain
     * Key: Node UUID
     * Value: Block reason/metadata
     *
     * Note: This is network-specific blocking. CXNET-level blocks are tracked separately.
     */
    public java.util.concurrent.ConcurrentHashMap<String, String> blockedNodes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Registered/approved nodes for whitelist mode
     * Populated by reading REGISTER_NODE events from c1 (Admin) chain
     * Only contains node UUIDs that have been explicitly registered
     *
     * Used when configuration.whitelistMode = true
     * If whitelist mode enabled and node not in this set: connection rejected
     */
    public java.util.Set<String> registeredNodes = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
