/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.api;

import us.anvildevelopment.cxnet.network.events.EventType;
import us.anvildevelopment.cxnet.network.events.NetworkEvent;

import java.nio.charset.StandardCharsets;

/**
 * Convenience plugin base for receiving plain-text MESSAGE events.
 *
 * <p>Override {@link #onMessage(String, String)} — the framework handles
 * event routing, byte decoding, and sender ID extraction automatically.
 *
 * <pre>
 * peer.addPlugin(new CXMessagePlugin() {
 *     public void onMessage(String from, String message) {
 *         System.out.println(from + ": " + message);
 *     }
 * });
 * </pre>
 */
public abstract class CXMessagePlugin extends CXPlugin {

    public CXMessagePlugin() {
        super(EventType.MESSAGE.name());
        this.dataLevel = DataLevel.NETWORK_EVENT;
    }

    @Override
    public final boolean handleEvent(Object data) {
        NetworkEvent ne = (NetworkEvent) data;
        String message = ne.d != null ? new String(ne.d, StandardCharsets.UTF_8) : "";
        onMessage(ne.iD, message);
        return true;
    }

    /**
     * Called when a MESSAGE event is received.
     *
     * @param senderID the CX node ID of the sender
     * @param message  the decoded message text
     */
    public abstract void onMessage(String senderID, String message);
}