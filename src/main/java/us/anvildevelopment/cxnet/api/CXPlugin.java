/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.api;

public abstract class CXPlugin {
    public String serviceName;
    public CXPlugin(String serviceName) {
        this.serviceName = serviceName;
    }
    public Class<?> type = null;
    public DataLevel dataLevel = null;

    /**
     * Handle an incoming event payload.
     * Override this if you do not need the sender's cxID.
     */
    public boolean handleEvent(Object data) { return true; }

    /**
     * Handle an incoming event payload with the origin sender's cxID.
     * Default implementation delegates to {@link #handleEvent(Object)}.
     * Override this to receive the sender identity alongside the event data.
     *
     * @param data       the event payload (type depends on {@link DataLevel})
     * @param senderCxID origin sender cxID ({@code ne.p.oCXID}), or null if unknown
     */
    public boolean handleEvent(Object data, String senderCxID) {
        return handleEvent(data);
    }
}
