/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import us.anvildevelopment.cxnet.network.events.EventType;

/**
 * Wrapper for OutputBundle that tracks retry attempts and timing
 * Failed events are moved to retry queue instead of blocking main output queue
 *
 * Delivery limits are declared per event in {@link EventType} (maxRetries, ttlMs). They are
 * resolved once at construction: an event this node knows takes its declared limits, anything
 * else takes the long-standing MAX_RETRIES / no-expiry defaults. Nothing downstream re-checks
 * the event type, so adding an event type never adds branches to this class.
 */
public class RetryBundle {
    public final OutputBundle bundle;
    /** Delivery attempts allowed for this bundle. Resolved at construction, never null. */
    public final int maxRetries;
    /** How long this bundle may sit queued before being dropped; 0 means no expiry. */
    public final long ttlMs;
    public int retryCount;
    public long nextRetryTime;
    public final long firstAttemptTime;
    public String lastError;
    public boolean convertedToCXN;  // Track if we've converted CXS → CXN fallback

    /** What the retry queue should do with a bundle on this pass. */
    public enum Disposition {
        /** Backoff has elapsed; transmit now. */
        READY,
        /** Backoff has not elapsed; leave queued. */
        WAITING,
        /** Delivery attempts exhausted; discard. */
        EXPIRED_RETRIES,
        /** Sat in the queue past its TTL; discard as stale. */
        EXPIRED_TTL
    }

    // Retry configuration
    public static final int MAX_RETRIES = 50;  // Default when the event type is unknown to this node
    public static final long INITIAL_RETRY_DELAY_MS = 5000;  // 5 seconds
    public static final double BACKOFF_MULTIPLIER = 4.0;     // Exponential backoff
    public static final long MAX_RETRY_DELAY_MS = 300000;    // 5 minutes max

    // CXS → CXN fallback configuration
    public static final int CXS_TO_CXN_THRESHOLD = 3;  // After N CXS failures, fall back to CXN broadcast with E2E

    public RetryBundle(OutputBundle bundle) {
        this.bundle = bundle;

        // Resolve the declared limits here and only here; unknown types take the defaults.
        EventType type = null;
        if (bundle != null && bundle.ne != null && bundle.ne.eT != null) {
            try {
                type = EventType.valueOf(bundle.ne.eT);
            } catch (IllegalArgumentException ignored) {
                // Event type not supported by this node; defaults apply.
            }
        }
        this.maxRetries = type != null ? type.maxRetries : MAX_RETRIES;
        this.ttlMs = type != null ? type.ttlMs : 0;

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
     * Single verdict for this bundle on the current retry pass.
     */
    public Disposition disposition() {
        if (retryCount >= maxRetries) return Disposition.EXPIRED_RETRIES;
        if (ttlMs > 0 && System.currentTimeMillis() - firstAttemptTime >= ttlMs) {
            return Disposition.EXPIRED_TTL;
        }
        return System.currentTimeMillis() >= nextRetryTime ? Disposition.READY : Disposition.WAITING;
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
        // Low-level discovery events cannot be converted: they target unknown peers by definition
        // and E2E CXN broadcast requires a known target cert. All other CXS events can fall back.
        String eventType = bundle.ne.eT;
        if ("CXHELLO".equals(eventType) || "CXHELLO_RESPONSE".equals(eventType) || "PeerFinding".equals(eventType)) {
            return false;
        }
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
