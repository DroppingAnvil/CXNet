/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

import java.io.Serializable;

/**
 * Payload object for MESSAGE events sent over CXN.
 * Serialized as cxJSON1 and signed via EventBuilder.signData() before dispatch.
 */
public class CXMessage implements Serializable {
    /** Plain-text message content */
    public String text;
    /** Sender-set timestamp (epoch milliseconds) */
    public long timestamp;

    public CXMessage() {}

    public CXMessage(String text) {
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }
}
