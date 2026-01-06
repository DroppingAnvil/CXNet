/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network;

import java.net.Socket;

public class CXPath {
    /**
     * Scope for transmission, usually CXN
     * CANNOT be null
     */
    public String scope;
    /**
     * Bridge to be used if available for network interaction
     * Will be null when directly using CX Protocol with Sockets
     */
    public String bridge;
    /**
     * The path the bridge provider will receive on use
     * Ex. HTTPS://cx.AnvilDevelopment.us/CX
     * SHOULD be null unless this request is using bridge
     */
    public String bridgeArg;
    /**
     * Network for transmission
     * Extremely case to case, most events will have network set
     * Some CXS or lower level messages will not include network information
     * ALL future request should include network
     * CXNET is for cross-network communication
     */
    public String network;
    /**
     * Address for direct socket CX connections
     * Can be null, Nodes can just use bridges for firewall/security
     */
    public String address;
    /**
     * Device ConnectX ID
     * CANNOT BE NULL
     * Every request without a ConnectX ID WILL fail
     */
    public String cxID;
    /**
     * Origin CXID, should match NetworkContainer.oD, this is persisted for storing events on disk without networking wrappers.
     */
    public String oCXID;
    /**
     * Version of resource, REMOTE DIRECTORY not implemented
     */
    public Integer version;
    /**
     * Resource ID on chain, REMOTE DIRECTORY not implemented
     */
    public String resourceID;
    /**
     * Blockchain ID for recording events, view blockchain permissions
     */
    public Long chainID;


    public Integer pt;
    public String sAR;

    public Scope getScope() {
        return Scope.valueOf(scope);
    }

    public static CXPath getPathFromString(String address) {
        CXPath path = new CXPath();
        path.address = address;
        if (isBridge(path)) {
            String[] parts = address.split(":", 2);
            path.bridge = parts[0];
            path.bridgeArg = parts[1];
        } else {
            String[] sar = path.address.split(":");
            path.sAR = sar[0];
            path.pt = Integer.parseInt(sar[1]);
        }
        return path;
    }

    public static Boolean isBridge(CXPath cxPath) {
        return (cxPath.address.contains(":") && !cxPath.address.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:.*"));
    }

    public static Boolean isSocket(CXPath cxPath) {
        return (cxPath.sAR != null);
    }
}
