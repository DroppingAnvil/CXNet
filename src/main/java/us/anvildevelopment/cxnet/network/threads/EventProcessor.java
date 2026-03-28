/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.threads;

import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.NodeMesh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventProcessor implements Runnable{
    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    public static boolean active = true;
    private NodeMesh nodeMesh;

    public EventProcessor(NodeMesh nodeMesh) {
        this.nodeMesh = nodeMesh;
    }

    @Override
    public void run() {
        while (active) {
            nodeMesh.in.processEvent();
            try {
                Thread.sleep(NodeConfig.IO_THREAD_SLEEP);
            } catch (InterruptedException e) {
                log.error("EventProcessor interrupted", e);
            }
        }
    }
}
