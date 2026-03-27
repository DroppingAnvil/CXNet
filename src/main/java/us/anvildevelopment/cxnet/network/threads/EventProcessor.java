/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.threads;

import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.NodeMesh;

public class EventProcessor implements Runnable{
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
                e.printStackTrace();
            }
        }
    }
}
