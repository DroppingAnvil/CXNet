/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Correlates an outbound APP_REQUEST with the APP_RESPONSE that answers it, keyed by the event sid.
 *
 * This is the in-process equivalent of the loopback bridge's {@code pendingAppHTML} map. That map
 * lives inside HTTPBridgeProvider and is reachable only from the servlet, which is why a CXApp
 * could previously be called from a browser and from nowhere else. The mechanism is the same; it
 * is simply owned by ConnectX here so both callers can use it.
 *
 * The two registries stay independent rather than being merged. Both are keyed by sid, a browser
 * request has no entry here and an in-process request has no bridge queue, so each is a no-op for
 * the other's traffic and the browser path is unaffected.
 *
 * -------------------------------------------------------------------------
 * THREADING
 * -------------------------------------------------------------------------
 * {@link #complete} is called from the EventProcessor, which is a single thread serving the whole
 * mesh. It never completes a future on that thread. Doing so would run every caller's
 * {@code thenApply}, {@code thenAccept} and callback chain inline on the EventProcessor, which is
 * the exact stall CXAppDispatcher exists to prevent, reintroduced through the public API. All
 * completions are handed to the dispatcher instead, so caller code runs on an app lane.
 *
 * A timeout completes the future exceptionally with {@link TimeoutException} on the common
 * ForkJoinPool delay scheduler, not on the EventProcessor, so the same guarantee holds when no
 * response ever arrives.
 */
public final class CXAppRequests {
    private static final Logger log = LoggerFactory.getLogger(CXAppRequests.class);

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final CXAppDispatcher dispatcher;

    public CXAppRequests(CXAppDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    private static final class Pending {
        final CompletableFuture<CXAppResponse> future;
        final String appID;
        /** Peer this request was addressed to. Only that peer may answer it; see expects(). */
        final String targetCXID;

        Pending(CompletableFuture<CXAppResponse> future, String appID, String targetCXID) {
            this.future     = future;
            this.appID      = appID;
            this.targetCXID = targetCXID;
        }
    }

    /**
     * Register a request awaiting its response.
     *
     * @param sid        the event sid, which the responder echoes back
     * @param appID      the app being called, used to pick the lane the completion runs on
     * @param targetCXID the peer being asked; recorded so only that peer can answer
     * @param ttlMs      how long to wait before failing with {@link TimeoutException}
     * @return a future completed with the response, or completed exceptionally on timeout
     */
    public CompletableFuture<CXAppResponse> register(String sid, String appID, String targetCXID, long ttlMs) {
        CompletableFuture<CXAppResponse> f = new CompletableFuture<>();
        pending.put(sid, new Pending(f, appID, targetCXID));
        // Removal is attached before the timeout so it runs on either outcome. Without this the map
        // would retain an entry for every request that was never answered.
        f.whenComplete((r, t) -> pending.remove(sid));
        f.orTimeout(ttlMs, TimeUnit.MILLISECONDS);
        return f;
    }

    /**
     * Deliver a response to whoever is waiting on its sid. Called from the NodeMesh APP_RESPONSE
     * handler on the EventProcessor.
     *
     * An unknown sid is normal and not an error: it means the response belongs to the browser
     * bridge, or to a request that already timed out and gave up.
     */
    public void complete(String sid, CXAppResponse response) {
        if (sid == null) return;
        Pending p = pending.remove(sid);
        if (p == null) return;

        // Never completed inline; see THREADING above.
        boolean queued = dispatcher.submit(p.appID, () -> p.future.complete(response));
        if (!queued) {
            // The lane is saturated. Failing the future is better than dropping the response and
            // leaving the caller to wait out a timeout it can no longer learn anything from.
            log.warn("[CXApp] Response for '{}' could not be delivered, lane full (sid {})", p.appID, sid);
            p.future.completeExceptionally(
                    new IllegalStateException("CXApp lane saturated, response discarded"));
        }
    }

    /**
     * Whether this node actually issued the request a response claims to answer.
     *
     * All three must match a pending entry: the sid, the app named in the response payload, and
     * the peer the request was sent to. Without this an APP_RESPONSE was applied on the strength
     * of its own payload alone, so any reachable peer could send an unsolicited response naming a
     * registered app and have applyAndRender write its values into that client's fields.
     *
     * @param peerCXID the response's origin, not its transmitter, so a relayed response from the
     *                 peer that was asked still matches
     */
    public boolean expects(String sid, String appID, String peerCXID) {
        if (sid == null || appID == null || peerCXID == null) return false;
        Pending p = pending.get(sid);
        return p != null && appID.equals(p.appID) && peerCXID.equals(p.targetCXID);
    }

    /** Number of requests currently awaiting a response. Diagnostics only. */
    public int pendingCount() {
        return pending.size();
    }
}
