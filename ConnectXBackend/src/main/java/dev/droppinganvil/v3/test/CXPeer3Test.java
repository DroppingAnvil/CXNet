/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.events.NodeModeration;
import dev.droppinganvil.v3.network.events.NodeRegistration;
import dev.droppinganvil.v3.network.events.PermissionChange;
import dev.droppinganvil.v3.network.events.ChainStatus;
import dev.droppinganvil.v3.network.events.SeedExchange;
/**
 * This class exist to test the ease of use of ConnectX API as well as test networking functionality
 * Peer3 will be created or loaded send message "1234" to 01
 */
public class CXPeer3Test {
    public static ConnectX peer3 = null;
    public static void main(String... args) throws Exception {
        peer3 = new ConnectX("CX-PEER3", 49158, "03006000-0400-0500-0000-007000000001", "Peer3");
        peer3.updateHTTPBridgePort(8081);
        peer3.setPublicBridgeAddress("cxHTTP1", "https://cx7.anvildevelopment.us/cx");
        peer3.buildEvent(EventType.MESSAGE, "1234".getBytes("UTF-8")).toPeer("00000000-0000-0000-0000-000000000001").signData().queue();

        // BLOCK_NODE test - block a node on CXNET
        String targetNode = "02000000-0000-0000-0000-000000000001";
        NodeModeration block = new NodeModeration("CXNET", targetNode, "peer3 test block");
        peer3.buildEvent(EventType.BLOCK_NODE, ConnectX.serialize("cxJSON1", block).getBytes("UTF-8"))
            .toPeer("00000000-0000-0000-0000-000000000001").signData().queue();

        // UNBLOCK_NODE test - reverse the block
        NodeModeration unblock = new NodeModeration("CXNET", targetNode, null);
        peer3.buildEvent(EventType.UNBLOCK_NODE, ConnectX.serialize("cxJSON1", unblock).getBytes("UTF-8"))
            .toPeer("00000000-0000-0000-0000-000000000001").signData().queue();

        // REGISTER_NODE test
        NodeRegistration register = new NodeRegistration("CXNET", targetNode, peer3.getOwnID());
        peer3.buildEvent(EventType.REGISTER_NODE, ConnectX.serialize("cxJSON1", register).getBytes("UTF-8"))
            .toPeer("00000000-0000-0000-0000-000000000001").signData().queue();

        // GRANT_PERMISSION test
        PermissionChange grant = new PermissionChange("CXNET", targetNode, "Record", 3L, 10);
        peer3.buildEvent(EventType.GRANT_PERMISSION, ConnectX.serialize("cxJSON1", grant).getBytes("UTF-8"))
            .toPeer("00000000-0000-0000-0000-000000000001").signData().queue();

        // REVOKE_PERMISSION test - same fields, priority left null
        PermissionChange revoke = new PermissionChange("CXNET", targetNode, "Record", 3L, null);
        peer3.buildEvent(EventType.REVOKE_PERMISSION, ConnectX.serialize("cxJSON1", revoke).getBytes("UTF-8"))
            .toPeer("00000000-0000-0000-0000-000000000001").signData().queue();

        // SEED_REQUEST test
        peer3.buildEvent(EventType.SEED_REQUEST, ConnectX.serialize("cxJSON1", new SeedExchange("CXNET")).getBytes("UTF-8"))
            .toPeer("00000000-0000-0000-0000-000000000001").signData().queue();

        // CHAIN_STATUS_REQUEST test
        peer3.buildEvent(EventType.CHAIN_STATUS_REQUEST, ConnectX.serialize("cxJSON1", new ChainStatus("CXNET")).getBytes("UTF-8"))
            .toPeer("00000000-0000-0000-0000-000000000001").signData().queue();
    }
}
