/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.threads;

import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.OutConnectionController;
import us.anvildevelopment.cxnet.network.nodemesh.OutputBundle;
import us.anvildevelopment.cxnet.network.nodemesh.RetryBundle;

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
        String peerID = outController.connectXAPI.getOwnID();
        String peerShort = (peerID != null && peerID.length() >= 8) ? peerID.substring(0, 8) : "UNKNOWN";
        System.out.println("[OutputProcessor] Thread started for peer " + peerShort);
        int loopCount = 0;

        while (active) {
            try {
                loopCount++;
                OutputBundle bundle;
                int queueSizeBefore;
                synchronized (outController.connectXAPI.outputQueue) {
                    queueSizeBefore = outController.connectXAPI.outputQueue.size();
                    bundle = outController.connectXAPI.outputQueue.poll();
                }

                // Log every 100 iterations OR when queue is large (only if enabled)
                if (NodeConfig.enableOutLoopLogging && (loopCount % 100 == 0 || queueSizeBefore > 5)) {
                    System.out.println("[OUT-LOOP] Peer " + peerShort + " iteration " + loopCount +
                        ", queue: " + queueSizeBefore + ", polled: " + (bundle != null));
                }

                // Debug: Log if we polled from a non-empty queue
                if (queueSizeBefore > 10) {
                    // Log when queue is large (stuck?)
                    System.out.println("[poll-DEBUG] Peer " + peerShort + " queue size: " + queueSizeBefore +
                        ", polled: " + (bundle != null ? "YES" : "NULL"));
                    if (bundle != null && bundle.ne != null && bundle.ne.eT != null) {
                        System.out.println("[poll-DEBUG]   -> Event type: " + bundle.ne.eT);
                    }
                } else if (bundle != null) {
                    String et = (bundle.ne != null && bundle.ne.eT != null) ? bundle.ne.eT : "NULL";
                    if (et.contains("HELLO")) {
                        System.out.println("[poll-DEBUG] Peer " + peerShort + " polled " + et);
                    }
                }

                if (bundle != null) {
                    String eventType = "NULL";
                    String nodeAddr = "NULL";
                    boolean hasPerm = false;

                    try {
                        if (bundle.ne != null) {
                            eventType = bundle.ne.eT != null ? bundle.ne.eT : "NULL-eT";
                            hasPerm = (bundle.ne.p != null);
                        } else {
                            eventType = "NULL-ne";
                        }
                        if (bundle.n != null) {
                            nodeAddr = bundle.n.addr != null ? bundle.n.addr : "NULL-addr";
                        }
                    } catch (Exception ex) {
                        eventType = "EXCEPTION:" + ex.getMessage();
                    }

                    // Log EVERY bundle for debugging
                    System.out.println("[OUT-DEBUG] Type=" + eventType + ", Node=" + nodeAddr + ", Perm=" + hasPerm);

                    try {
                        outController.transmitEvent(bundle);
                    } catch (Exception e) {
                        // Move failed event to retry queue instead of dropping it
                        // This prevents failed events (e.g., to offline EPOCH) from blocking the output queue
                        RetryBundle retryBundle =
                            new RetryBundle(bundle);
                        retryBundle.scheduleNextRetry(e.getMessage());

                        outController.connectXAPI.retryQueue.add(retryBundle);

                        System.err.println("[OUT-ERROR] " + eventType + " to " + nodeAddr + " failed: " + e.getMessage());
                        System.err.println("[RETRY-QUEUE] Added to retry queue (retry 1/" +
                            RetryBundle.MAX_RETRIES +
                            " in " + (RetryBundle.INITIAL_RETRY_DELAY_MS / 1000) + "s)");
                    }
                }

                Thread.sleep(NodeConfig.IO_THREAD_SLEEP);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}