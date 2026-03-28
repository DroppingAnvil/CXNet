/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import us.anvildevelopment.cxnet.crypt.core.exceptions.DecryptionFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;

public class InConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(InConnectionManager.class);
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
            log.error("Error processing event", e);
        }
    }
}
