/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.threads;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.nodemesh.NodeConfig;
import dev.droppinganvil.v3.network.nodemesh.OutConnectionController;
import dev.droppinganvil.v3.network.nodemesh.RetryBundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Processes retry queue for failed network events
 * Uses exponential backoff to retry failed transmissions without blocking main output queue
 */
public class RetryProcessor implements Runnable {
    public static boolean active = true;
    private OutConnectionController outController;
    private static final long CHECK_INTERVAL_MS = 1000; // Check retry queue every second

    public RetryProcessor(OutConnectionController outController) {
        this.outController = outController;
    }

    @Override
    public void run() {
        String peerID = outController.connectXAPI.getOwnID();
        String peerShort = (peerID != null && peerID.length() >= 8) ? peerID.substring(0, 8) : "UNKNOWN";
        System.out.println("[RetryProcessor] Thread started for peer " + peerShort);

        while (active) {
            try {
                // Collect bundles ready for retry
                List<RetryBundle> toRetry = new ArrayList<>();
                List<RetryBundle> toRequeue = new ArrayList<>();

                synchronized (outController.connectXAPI.retryQueue) {
                    int queueSize = outController.connectXAPI.retryQueue.size();

                    // Process all bundles in retry queue
                    RetryBundle bundle;
                    while ((bundle = outController.connectXAPI.retryQueue.poll()) != null) {
                        if (bundle.hasExceededMaxRetries()) {
                            // Drop after max retries
                            System.err.println("[RETRY-DROP] " + bundle.getEventType() + " to " +
                                bundle.getTargetAddress() + " dropped after " + bundle.retryCount + " retries");
                            System.err.println("[RETRY-DROP]   First attempt: " +
                                ((System.currentTimeMillis() - bundle.firstAttemptTime) / 1000) + "s ago");
                            System.err.println("[RETRY-DROP]   Last error: " + bundle.lastError);
                        } else if (bundle.shouldRetry()) {
                            // Ready for retry
                            toRetry.add(bundle);
                        } else {
                            // Not ready yet, requeue
                            toRequeue.add(bundle);
                        }
                    }

                    // Requeue bundles not ready for retry
                    for (RetryBundle b : toRequeue) {
                        outController.connectXAPI.retryQueue.add(b);
                    }

                    // Log retry queue status periodically
                    if (queueSize > 0) {
                        long nextRetryIn = toRequeue.isEmpty() ? 0 :
                            Math.max(0, toRequeue.get(0).nextRetryTime - System.currentTimeMillis()) / 1000;
                        System.out.println("[RETRY-QUEUE] Size: " + queueSize +
                            ", ready: " + toRetry.size() +
                            ", waiting: " + toRequeue.size() +
                            (toRequeue.isEmpty() ? "" : ", next in " + nextRetryIn + "s"));
                    }
                }

                // Attempt retries outside of synchronized block
                for (RetryBundle bundle : toRetry) {
                    String eventType = bundle.getEventType();
                    String nodeAddr = bundle.getTargetAddress();

                    // Check if we should convert CXS → CXN fallback with E2E
                    if (bundle.shouldConvertToCXN()) {
                        String targetPeerID = bundle.getTargetPeerID();
                        String networkID = bundle.getNetworkID();

                        System.out.println("[CXS→CXN-FALLBACK] " + eventType + " to peer " +
                            (targetPeerID != null && targetPeerID.length() >= 8 ? targetPeerID.substring(0, 8) : "UNKNOWN") +
                            " failed " + bundle.retryCount + " times, converting to CXN with E2E encryption");

                        try {
                            // Re-encrypt the event data with E2E encryption for the target peer
                            // Note: The event data is already signed via .signData(), we're adding E2E layer
                            byte[] eventData = bundle.bundle.ne.d;

                            // Create new event using EventBuilder with E2E encryption
                            dev.droppinganvil.v3.network.events.EventType et =
                                dev.droppinganvil.v3.network.events.EventType.valueOf(bundle.bundle.ne.eT);

                            outController.connectXAPI.buildEvent(et, eventData)
                                .toNetwork(networkID != null ? networkID : "CXNET")  // Convert to CXN scope for the event's network
                                .addRecipient(targetPeerID)  // Add original target as E2E recipient
                                .encrypt()  // Apply E2E encryption
                                .queue();

                            bundle.convertedToCXN = true;
                            System.out.println("[CXS→CXN-SUCCESS] " + eventType +
                                " converted to E2E-encrypted CXN broadcast for network " + (networkID != null ? networkID : "CXNET"));

                            // Don't requeue - we've created a new event via EventBuilder
                            continue;

                        } catch (Exception e) {
                            System.err.println("[CXS→CXN-FAILED] Failed to convert " + eventType +
                                " to CXN: " + e.getMessage());
                            // Fall through to normal retry logic
                        }
                    }

                    System.out.println("[RETRY-ATTEMPT] " + eventType + " to " + nodeAddr +
                        " (attempt " + (bundle.retryCount + 1) + "/" + RetryBundle.MAX_RETRIES + ")");

                    try {
                        outController.transmitEvent(bundle.bundle);
                        // Success! Don't requeue
                        System.out.println("[RETRY-SUCCESS] " + eventType + " to " + nodeAddr +
                            " succeeded on retry " + (bundle.retryCount + 1));
                    } catch (Exception e) {
                        // Failed again, schedule next retry
                        bundle.scheduleNextRetry(e.getMessage());

                        synchronized (outController.connectXAPI.retryQueue) {
                            outController.connectXAPI.retryQueue.add(bundle);
                        }

                        long nextRetryDelay = (bundle.nextRetryTime - System.currentTimeMillis()) / 1000;
                        System.err.println("[RETRY-FAILED] " + eventType + " to " + nodeAddr +
                            " failed again: " + e.getMessage());
                        System.err.println("[RETRY-QUEUE] Retry " + (bundle.retryCount) + "/" +
                            RetryBundle.MAX_RETRIES + " in " + nextRetryDelay + "s");
                    }
                }

                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                System.err.println("[RetryProcessor] Interrupted: " + e.getMessage());
                break;
            } catch (Exception e) {
                System.err.println("[RetryProcessor] Unexpected error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("[RetryProcessor] Thread stopped for peer " + peerShort);
    }
}
