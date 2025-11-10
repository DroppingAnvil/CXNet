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
