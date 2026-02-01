/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.events.EventType;

public class CXPeer3Test {
    public static ConnectX peer3 = null;
    public static void main(String... args) throws Exception {
        peer3 = new ConnectX("CX-PEER3", 49154, "03006000-0400-0500-0000-007000000001", "Peer3");
        peer3.updateHTTPBridgePort(8081);
        peer3.setPublicBridgeAddress("cxHTTP1", "https://cx3.anvildevelopment.us/cx");

        peer3.buildEvent(EventType.MESSAGE, "1234".getBytes("UTF-8")).toPeer("00000000-0000-0000-0000-000000000001").addRecipient("00000000-0000-0000-0000-000000000001").encrypt().queue();
        peer3.buildEvent(EventType.MESSAGE, "1234".getBytes("UTF-8")).toPeer("00000000-0000-0000-0000-000000000001").addRecipient("00000000-0000-0000-0000-000000000001").encrypt().queue();
        peer3.buildEvent(EventType.MESSAGE, "1234".getBytes("UTF-8")).toPeer("00000000-0000-0000-0000-000000000001").addRecipient("00000000-0000-0000-0000-000000000001").encrypt().queue();
        peer3.buildEvent(EventType.MESSAGE, "1234".getBytes("UTF-8")).toPeer("00000000-0000-0000-0000-000000000001").addRecipient("00000000-0000-0000-0000-000000000001").encrypt().queue();
        peer3.buildEvent(EventType.MESSAGE, "1234".getBytes("UTF-8")).toPeer("00000000-0000-0000-0000-000000000001").addRecipient("00000000-0000-0000-0000-000000000001").signData().queue();

    }
}
