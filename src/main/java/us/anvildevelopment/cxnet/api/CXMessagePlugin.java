/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.api;

import us.anvildevelopment.cxnet.network.events.CXMessage;
import us.anvildevelopment.cxnet.network.events.EventType;

/**
 * Convenience plugin base for receiving MESSAGE events.
 *
 * <p>The event payload must be a {@link CXMessage} serialized as cxJSON1
 * and signed via {@code EventBuilder.signData()} before dispatch.
 * The framework deserializes and verifies the payload automatically,
 * then passes the origin sender cxID alongside the message.
 *
 * <pre>
 * peer.addPlugin(new CXMessagePlugin() {
 *     public void onMessage(String senderID, CXMessage message) {
 *         System.out.println(senderID + ": " + message.text);
 *     }
 * });
 * </pre>
 */
public abstract class CXMessagePlugin extends CXPlugin {

    public CXMessagePlugin() {
        super(EventType.MESSAGE.name());
        this.type = CXMessage.class;
        this.dataLevel = DataLevel.OBJECT;
    }

    @Override
    public final boolean handleEvent(Object data, String senderCxID) {
        if (!(data instanceof CXMessage)) return false;
        onMessage(senderCxID, (CXMessage) data);
        return true;
    }

    /**
     * Called when a verified MESSAGE event is received.
     *
     * @param senderID origin sender cxID (from {@code ne.p.oCXID}), or null if not resolvable
     * @param message  the deserialized and signature-verified message object
     */
    public abstract void onMessage(String senderID, CXMessage message);
}
