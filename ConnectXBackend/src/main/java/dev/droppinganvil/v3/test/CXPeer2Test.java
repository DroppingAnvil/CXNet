/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.events.EventType;

/**
 * This class exist to test the ease of use of ConnectX API as well as test networking functionality
 * Peer2 will be created or loaded send message "Hello from peer2!" to 01
 */
public class CXPeer2Test {
    public static ConnectX peer2 = null;

    public static void main(String... args) throws Exception {
        peer2 = new ConnectX("CX-PEER2", 49153, "02000000-0000-0000-0000-000000000001", "Peer2");
        peer2.updateHTTPBridgePort(8087);
        peer2.setPublicBridgeAddress("cxHTTP1", "https://cx2.anvildevelopment.us/cx");
        peer2.buildEvent(EventType.MESSAGE, "Hello from peer2!".getBytes("UTF-8")).signData().toPeer("00000000-0000-0000-0000-000000000001").queue();
    }
}
