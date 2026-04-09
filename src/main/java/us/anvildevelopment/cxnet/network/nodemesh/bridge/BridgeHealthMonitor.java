/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the health of individual bridge addresses and temporarily suppresses
 * routing to addresses that are consistently failing.
 *
 * <p>Degraded state is always temporary. An address is freed either when its
 * probe succeeds, or after {@link #MAX_DEGRADED_MS} regardless -- no address is
 * ever permanently blocked.
 *
 * <p>Intended use:
 * <pre>
 *   // Before routing:
 *   if (!BridgeHealthMonitor.isHealthy(address)) { skip }
 *
 *   // After a transmit attempt:
 *   BridgeHealthMonitor.recordSuccess(address);  // on success
 *   BridgeHealthMonitor.recordFailure(address, connectX);  // on failure
 * </pre>
 */
public final class BridgeHealthMonitor {
    private static final Logger log = LoggerFactory.getLogger(BridgeHealthMonitor.class);

    /** Consecutive failures before an address is marked degraded. */
    private static final int DEGRADED_THRESHOLD = 3;

    /** How long an address stays degraded before auto-recovery (5 minutes). */
    public static final long DEGRADED_DURATION_MS = 5 * 60 * 1000L;

    /** Hard ceiling: even if probes keep failing, always free after this (20 minutes). */
    private static final long MAX_DEGRADED_MS = 20 * 60 * 1000L;

    /** How often the probe thread re-checks degraded addresses. */
    private static final long PROBE_INTERVAL_MS = 60_000L;

    // address -> epoch-ms when degradation expires (0 or absent = healthy)
    private static final ConcurrentHashMap<String, Long> degradedUntil = new ConcurrentHashMap<>();
    // address -> epoch-ms of first degradation (for MAX_DEGRADED_MS ceiling)
    private static final ConcurrentHashMap<String, Long> degradedSince = new ConcurrentHashMap<>();
    // address -> consecutive failure count
    private static final ConcurrentHashMap<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

    private static volatile Thread probeThread;
    private static volatile ConnectX connectX;

    private BridgeHealthMonitor() {}

    /** Wire up the ConnectX instance used during active probing. Call once at startup. */
    public static void initialize(ConnectX cx) {
        connectX = cx;
    }

    /**
     * Returns true if the bridge address is currently healthy (or degradation has expired).
     * Cleans up auto-expired entries on the fly.
     */
    public static boolean isHealthy(String bridgeAddress) {
        Long until = degradedUntil.get(bridgeAddress);
        if (until == null) return true;
        if (System.currentTimeMillis() >= until) {
            // Degradation window expired -- auto-recover
            clearDegraded(bridgeAddress);
            log.info("[BridgeHealth] {} auto-recovered (timeout)", shorten(bridgeAddress));
            return true;
        }
        return false;
    }

    /**
     * Record a successful transmission to a bridge address.
     * Resets the failure counter and clears any degraded state.
     */
    public static void recordSuccess(String bridgeAddress) {
        failureCounts.remove(bridgeAddress);
        if (degradedUntil.containsKey(bridgeAddress)) {
            clearDegraded(bridgeAddress);
            log.info("[BridgeHealth] {} recovered after success", shorten(bridgeAddress));
        }
    }

    /**
     * Record a failed transmission attempt to a bridge address.
     * After {@link #DEGRADED_THRESHOLD} consecutive failures the address is marked degraded
     * and a background probe thread is started to detect recovery.
     */
    public static void recordFailure(String bridgeAddress) {
        AtomicInteger count = failureCounts.computeIfAbsent(bridgeAddress, k -> new AtomicInteger(0));
        int failures = count.incrementAndGet();
        if (failures >= DEGRADED_THRESHOLD && !degradedUntil.containsKey(bridgeAddress)) {
            markDegraded(bridgeAddress);
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static void markDegraded(String bridgeAddress) {
        long now = System.currentTimeMillis();
        degradedUntil.put(bridgeAddress, now + DEGRADED_DURATION_MS);
        degradedSince.put(bridgeAddress, now);
        log.warn("[BridgeHealth] {} marked DEGRADED for {}min (will probe every {}s for recovery)",
            shorten(bridgeAddress), DEGRADED_DURATION_MS / 60_000, PROBE_INTERVAL_MS / 1000);
        ensureProbeThreadRunning();
    }

    private static void clearDegraded(String bridgeAddress) {
        degradedUntil.remove(bridgeAddress);
        degradedSince.remove(bridgeAddress);
        failureCounts.remove(bridgeAddress);
    }

    private static void ensureProbeThreadRunning() {
        if (probeThread != null && probeThread.isAlive()) return;
        Thread t = new Thread(BridgeHealthMonitor::probeLoop, "bridge-health-probe");
        t.setDaemon(true);
        probeThread = t;
        t.start();
    }

    /**
     * Background daemon: periodically probes all currently-degraded addresses.
     * Exits automatically when no degraded addresses remain.
     */
    private static void probeLoop() {
        log.info("[BridgeHealth] Probe thread started");
        while (!degradedUntil.isEmpty()) {
            try {
                Thread.sleep(PROBE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long now = System.currentTimeMillis();
            for (String addr : degradedUntil.keySet()) {
                Long until = degradedUntil.get(addr);
                Long since = degradedSince.getOrDefault(addr, now);

                // Hard ceiling: free unconditionally after MAX_DEGRADED_MS
                if (now - since >= MAX_DEGRADED_MS) {
                    clearDegraded(addr);
                    log.info("[BridgeHealth] {} force-recovered after max degraded window", shorten(addr));
                    continue;
                }

                // Natural expiry check
                if (until != null && now >= until) {
                    clearDegraded(addr);
                    log.info("[BridgeHealth] {} auto-recovered (timeout expired)", shorten(addr));
                    continue;
                }

                // Active probe
                ConnectX cx = connectX;
                if (cx == null) continue;
                String[] parts = addr.split(":", 2);
                if (parts.length != 2) continue;
                BridgeProvider bridge = cx.getBridgeProvider(parts[0]);
                if (bridge == null) continue;

                boolean ok = false;
                try {
                    ok = bridge.probeHealth(addr);
                } catch (Exception e) {
                    log.debug("[BridgeHealth] Probe threw for {}: {}", shorten(addr), e.getMessage());
                }

                if (ok) {
                    clearDegraded(addr);
                    log.info("[BridgeHealth] {} probe succeeded -- restored to healthy", shorten(addr));
                } else {
                    // Extend degraded window from now
                    degradedUntil.put(addr, now + DEGRADED_DURATION_MS);
                    log.debug("[BridgeHealth] {} still unreachable, degraded window extended {}min",
                        shorten(addr), DEGRADED_DURATION_MS / 60_000);
                }
            }
        }
        log.info("[BridgeHealth] Probe thread exiting (no degraded bridges)");
    }

    private static String shorten(String addr) {
        return addr != null && addr.length() > 40 ? addr.substring(0, 40) + "..." : addr;
    }
}