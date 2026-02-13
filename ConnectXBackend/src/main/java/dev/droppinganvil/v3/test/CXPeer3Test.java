/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.events.EventType;
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
    }
}
