/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs CXApp handlers off the EventProcessor thread.
 *
 * EventProcessor is the single thread every inbound event is dispatched on, so a handler that
 * blocks there stalls the entire node: chat, peer discovery, seed consensus and block exchange
 * all stop until it returns. Handler bodies are arbitrary developer code and cannot be trusted
 * to stay non-blocking, so they are moved here instead.
 *
 * Work is spread across a shared bounded pool, but each appID owns a lane that is drained one
 * task at a time, so handlers for a given app never run concurrently and existing CXAppServer
 * implementations keep the serialization they were written against. Because a lane runs a single
 * task at a time, one app can never occupy more than one pool thread, and a blocked handler
 * stalls only its own lane. Its queue then fills and further requests are rejected, which the
 * caller answers as a failed response rather than letting the request hang.
 *
 * The drainer re-submits itself between tasks rather than looping, so a busy app returns its
 * thread to the pool and cannot starve other apps while it has a backlog.
 *
 * Lanes are never removed: submit() is only reached for a registered app, so the map is bounded
 * by the number of registered apps. Removing them would race a concurrent submitter holding the
 * same lane reference, which would strand that task.
 */
public class CXAppDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CXAppDispatcher.class);

    private static final class Lane {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean running;
    }

    private final ExecutorService pool;
    private final ConcurrentHashMap<String, Lane> lanes = new ConcurrentHashMap<>();
    private final int maxQueuedPerApp;
    private final long warnAfterMs;

    public CXAppDispatcher(int threads, int maxQueuedPerApp, long warnAfterMs) {
        this.maxQueuedPerApp = maxQueuedPerApp;
        this.warnAfterMs = warnAfterMs;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "CXApp-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        log.info("[CXApp] Dispatcher started with {} thread(s), {} queued task(s) per app", threads, maxQueuedPerApp);
    }

    /**
     * Queue a handler for execution on this app's lane.
     *
     * @return false if the app's queue is full, meaning the caller should answer with a failure
     *         response; the task is not retained in that case.
     */
    public boolean submit(String appID, Runnable task) {
        Lane lane = lanes.computeIfAbsent(appID, k -> new Lane());
        synchronized (lane) {
            if (lane.queue.size() >= maxQueuedPerApp) {
                log.warn("[CXApp] Lane for '{}' is full ({} queued), rejecting request", appID, maxQueuedPerApp);
                return false;
            }
            lane.queue.add(task);
            if (lane.running) return true;   // an existing drainer will pick this up
            lane.running = true;
        }
        pool.execute(() -> drain(appID, lane));
        return true;
    }

    private void drain(String appID, Lane lane) {
        Runnable task;
        synchronized (lane) {
            task = lane.queue.poll();
            if (task == null) {
                lane.running = false;
                return;
            }
        }

        long started = System.currentTimeMillis();
        try {
            task.run();
        } catch (Throwable t) {
            // A handler must never kill the pool thread or leave the lane marked running.
            log.error("[CXApp] Handler for '{}' threw: {}", appID, t.toString());
        } finally {
            long took = System.currentTimeMillis() - started;
            if (warnAfterMs > 0 && took >= warnAfterMs) {
                log.warn("[CXApp] Handler for '{}' took {}ms; handlers should not block", appID, took);
            }
        }

        boolean more;
        synchronized (lane) {
            more = !lane.queue.isEmpty();
            if (!more) lane.running = false;
        }
        if (more) pool.execute(() -> drain(appID, lane));
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
