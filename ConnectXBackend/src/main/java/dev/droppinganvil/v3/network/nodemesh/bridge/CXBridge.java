/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh.bridge;

import dev.droppinganvil.v3.network.CXPath;
import dev.droppinganvil.v3.network.nodemesh.OutputBundle;

import java.net.Socket;

public interface CXBridge {
    String getProtocol();
    Integer getVersion();
    void setup();
    Socket connect(CXPath path, byte[] data);
    boolean getDirectSocket();
    boolean transmitEvent(CXPath path, byte[] data);
}
