/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh;

/**
 * Wrapper for OutputBundle that tracks retry attempts and timing
 * Failed events are moved to retry queue instead of blocking main output queue
 */
public class RetryBundle {
    public final OutputBundle bundle;
    public int retryCount;
    public long nextRetryTime;
    public final long firstAttemptTime;
    public String lastError;
    public boolean convertedToCXN;  // Track if we've converted CXS → CXN fallback

    // Retry configuration
    public static final int MAX_RETRIES = 50;  // Never discard events, keep retrying
    public static final long INITIAL_RETRY_DELAY_MS = 5000;  // 5 seconds
    public static final double BACKOFF_MULTIPLIER = 4.0;     // Exponential backoff
    public static final long MAX_RETRY_DELAY_MS = 300000;    // 5 minutes max

    // CXS → CXN fallback configuration
    public static final int CXS_TO_CXN_THRESHOLD = 15;  // After 3 CXS failures, try CXN with E2E

    public RetryBundle(OutputBundle bundle) {
        this.bundle = bundle;
        this.retryCount = 0;
        this.firstAttemptTime = System.currentTimeMillis();
        this.nextRetryTime = System.currentTimeMillis() + INITIAL_RETRY_DELAY_MS;
    }

    /**
     * Calculate next retry time using exponential backoff
     */
    public void scheduleNextRetry(String error) {
        this.retryCount++;
        this.lastError = error;

        // Exponential backoff: 5s, 10s, 20s, 40s, 80s, max 5min
        long delay = (long) (INITIAL_RETRY_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, retryCount - 1));
        delay = Math.min(delay, MAX_RETRY_DELAY_MS);

        this.nextRetryTime = System.currentTimeMillis() + delay;
    }

    /**
     * Check if this bundle should be retried
     */
    public boolean shouldRetry() {
        return retryCount < MAX_RETRIES && System.currentTimeMillis() >= nextRetryTime;
    }

    /**
     * Check if this bundle has exceeded max retries
     */
    public boolean hasExceededMaxRetries() {
        return retryCount >= MAX_RETRIES;
    }

    /**
     * Get event type for logging
     */
    public String getEventType() {
        if (bundle != null && bundle.ne != null && bundle.ne.eT != null) {
            return bundle.ne.eT;
        }
        return "UNKNOWN";
    }

    /**
     * Get target address for logging
     */
    public String getTargetAddress() {
        if (bundle != null && bundle.n != null && bundle.n.addr != null) {
            return bundle.n.addr;
        }
        return "UNKNOWN";
    }

    /**
     * Check if this bundle should be converted from CXS to CXN fallback
     * After N failures on direct peer-to-peer (CXS), try network broadcast (CXN) with E2E
     */
    public boolean shouldConvertToCXN() {
        if (convertedToCXN) {
            return false; // Already converted
        }
        if (retryCount < CXS_TO_CXN_THRESHOLD) {
            return false; // Not enough retries yet
        }
        if (bundle == null || bundle.ne == null || bundle.ne.p == null) {
            return false; // Invalid bundle
        }
        // Only convert CXS (peer-to-peer) events to CXN
        return "CXS".equals(bundle.ne.p.scope);
    }

    /**
     * Get target peer ID for E2E encryption when converting to CXN
     */
    public String getTargetPeerID() {
        if (bundle != null && bundle.ne != null && bundle.ne.p != null) {
            return bundle.ne.p.cxID;
        }
        return null;
    }

    /**
     * Get network ID for CXN fallback
     */
    public String getNetworkID() {
        if (bundle != null && bundle.ne != null && bundle.ne.p != null) {
            return bundle.ne.p.network;
        }
        return "CXNET"; // Default to CXNET if not specified
    }
}
