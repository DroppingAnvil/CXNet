/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.threads;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.nodemesh.NodeConfig;
import dev.droppinganvil.v3.network.nodemesh.OutConnectionController;
import dev.droppinganvil.v3.network.nodemesh.OutputBundle;

/**
 * Processes outbound network events from outputQueue
 */
public class OutputProcessor implements Runnable {
    public static boolean active = true;
    private OutConnectionController outController;

    public OutputProcessor(OutConnectionController outController) {
        this.outController = outController;
    }

    @Override
    public void run() {
        while (active) {
            try {
                OutputBundle bundle;
                synchronized (outController.connectXAPI.outputQueue) {
                    bundle = outController.connectXAPI.outputQueue.poll();
                }

                if (bundle != null) {
                    try {
                        outController.transmitEvent(bundle);
                    } catch (Exception e) {
                        e.printStackTrace();
                        //TODO retry logic or dead letter queue
                    }
                }

                Thread.sleep(NodeConfig.IO_THREAD_SLEEP);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}