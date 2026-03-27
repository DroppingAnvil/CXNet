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
    public boolean handleEvent(Object data) {return true;}
}
