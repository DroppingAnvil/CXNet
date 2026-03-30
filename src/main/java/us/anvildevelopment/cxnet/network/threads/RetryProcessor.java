/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.threads;

import us.anvildevelopment.cxnet.network.events.EventType;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.OutConnectionController;
import us.anvildevelopment.cxnet.network.nodemesh.RetryBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Processes retry queue for failed network events
 * Uses exponential backoff to retry failed transmissions without blocking main output queue
 */
public class RetryProcessor implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(RetryProcessor.class);
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
        log.info("[RetryProcessor] Thread started for peer {}", peerShort);

        while (active) {
            try {
                // Collect bundles ready for retry
                List<RetryBundle> toRetry = new ArrayList<>();
                List<RetryBundle> toRequeue = new ArrayList<>();

                int queueSize = outController.connectXAPI.retryQueue.size();

                    // Process all bundles in retry queue
                    RetryBundle polled;
                    while ((polled = outController.connectXAPI.retryQueue.poll()) != null) {
                        if (polled.hasExceededMaxRetries()) {
                            // Drop after max retries
                            log.error("[RETRY-DROP] {} to {} dropped after {} retries",
                                polled.getEventType(), polled.getTargetAddress(), polled.retryCount);
                            log.error("[RETRY-DROP]   First attempt: {}s ago",
                                ((System.currentTimeMillis() - polled.firstAttemptTime) / 1000));
                            log.error("[RETRY-DROP]   Last error: {}", polled.lastError);
                        } else if (polled.shouldRetry()) {
                            // Ready for retry
                            toRetry.add(polled);
                        } else {
                            // Not ready yet, requeue
                            toRequeue.add(polled);
                        }
                    }

                    // Requeue bundles not ready for retry
                outController.connectXAPI.retryQueue.addAll(toRequeue);

                    // Log retry queue status periodically
                    if (NodeConfig.DEBUG && queueSize > 0) {
                        long nextRetryIn = toRequeue.isEmpty() ? 0 :
                            Math.max(0, toRequeue.get(0).nextRetryTime - System.currentTimeMillis()) / 1000;
                        log.info("[RETRY-QUEUE] Size: {}, ready: {}, waiting: {}{}",
                            queueSize, toRetry.size(), toRequeue.size(),
                            (toRequeue.isEmpty() ? "" : ", next in " + nextRetryIn + "s"));
                    }

                // Attempt retries outside of synchronized block
                for (RetryBundle bundle : toRetry) {
                    String eventType = bundle.getEventType();
                    String nodeAddr = bundle.getTargetAddress();

                    // Check if we should convert CXS → CXN fallback with E2E
                    if (bundle.shouldConvertToCXN()) {
                        String targetPeerID = bundle.getTargetPeerID();
                        String networkID = bundle.getNetworkID();

                        log.info("[CXS->CXN-FALLBACK] {} to peer {} failed {} times, converting to CXN with E2E encryption",
                            eventType, (targetPeerID != null && targetPeerID.length() >= 8 ? targetPeerID.substring(0, 8) : "UNKNOWN"), bundle.retryCount);

                        try {
                            // Attempt node lookup to prime cert cache before E2E encrypt
                            try {
                                outController.connectXAPI.nodeMesh.peerDirectory.lookup(targetPeerID, true, true);
                            } catch (Exception lookupEx) {
                                log.error("[CXS->CXN-LOOKUP] Could not resolve peer {}: {}",
                                        (targetPeerID != null && targetPeerID.length() >= 8 ? targetPeerID.substring(0, 8) : "UNKNOWN"),
                                    lookupEx.getMessage());
                                // Continue anyway - cert may already be cached or lookup partial
                            }

                            // Re-encrypt the event data with E2E encryption for the target peer
                            byte[] eventData = bundle.bundle.ne.d;

                            EventType et =
                                EventType.valueOf(bundle.bundle.ne.eT);

                            outController.connectXAPI.buildEvent(et, eventData)
                                .toNetwork(networkID != null ? networkID : "CXNET")
                                .addRecipient(targetPeerID)
                                .encrypt()
                                .queue();

                            bundle.convertedToCXN = true;
                            log.info("[CXS->CXN-SUCCESS] {} converted to E2E-encrypted CXN broadcast for network {}",
                                eventType, (networkID != null ? networkID : "CXNET"));

                            // Don't requeue - new event created via EventBuilder
                            continue;

                        } catch (Exception e) {
                            // E2E is mandatory - do not fall back to plain CXS
                            log.error("[CXS->CXN-FAILED] E2E encrypt failed for {} (cert unavailable?): {} - will retry E2E",
                                eventType, e.getMessage());
                            bundle.scheduleNextRetry(e.getMessage());
                            outController.connectXAPI.retryQueue.add(bundle);
                            continue;
                        }
                    }

                    log.info("[RETRY-ATTEMPT] {} to {} (attempt {}/{})",
                        eventType, nodeAddr, (bundle.retryCount + 1), RetryBundle.MAX_RETRIES);

                    try {
                        outController.transmitEvent(bundle.bundle);
                        // Success! Don't requeue
                        log.info("[RETRY-SUCCESS] {} to {} succeeded on retry {}", eventType, nodeAddr, (bundle.retryCount + 1));
                    } catch (Exception e) {
                        // Failed again, schedule next retry
                        bundle.scheduleNextRetry(e.getMessage());

                        outController.connectXAPI.retryQueue.add(bundle);

                        long nextRetryDelay = (bundle.nextRetryTime - System.currentTimeMillis()) / 1000;
                        log.error("[RETRY-FAILED] {} to {} failed again: {}", eventType, nodeAddr, e.getMessage());
                        log.error("[RETRY-QUEUE] Retry {}/{} in {}s", bundle.retryCount, RetryBundle.MAX_RETRIES, nextRetryDelay);
                    }
                }

                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                log.error("[RetryProcessor] Interrupted: {}", e.getMessage());
                break;
            } catch (Exception e) {
                log.error("[RetryProcessor] Unexpected error", e);
            }
        }

        log.info("[RetryProcessor] Thread stopped for peer {}", peerShort);
    }
}
