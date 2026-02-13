/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.api;

import dev.droppinganvil.v3.network.events.NetworkEvent;

public abstract class CXPlugin {
    public String serviceName;
    public CXPlugin(String serviceName) {
        this.serviceName = serviceName;
    }
    public Class<?> type = null;
    public DataLevel dataLevel = null;
    public boolean handleEvent(Object data) {return true;}
}
