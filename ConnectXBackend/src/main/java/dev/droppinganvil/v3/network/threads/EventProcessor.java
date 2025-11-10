/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.threads;

import dev.droppinganvil.v3.network.nodemesh.NodeConfig;
import dev.droppinganvil.v3.network.nodemesh.NodeMesh;

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
