/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.crypt.core.exceptions.DecryptionFailureException;

import java.io.*;
import java.net.ServerSocket;

public class InConnectionManager {
    public ServerSocket serverSocket;  // Instance-specific (removed static to support multiple peers)
    private NodeMesh nodeMesh;

    public InConnectionManager(Integer port, NodeMesh nodeMesh) throws IOException {
        serverSocket = new ServerSocket(port);
        this.nodeMesh = nodeMesh;
    }

    /**
     * Process incoming network events from eventQueue
     */
    public void processEvent() {
        try {
            nodeMesh.processEvent();
        } catch (IOException | DecryptionFailureException e) {
            e.printStackTrace();
        }
    }
}
