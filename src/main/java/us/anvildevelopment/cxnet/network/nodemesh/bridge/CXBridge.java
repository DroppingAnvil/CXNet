/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh.bridge;

import us.anvildevelopment.cxnet.network.CXPath;

import java.net.Socket;

public interface CXBridge {
    String getProtocol();
    Integer getVersion();
    void setup();
    Socket connect(CXPath path, byte[] data);
    boolean getDirectSocket();
    boolean transmitEvent(CXPath path, byte[] data);
}
