/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

public class NodeConfig {
    /**
     * Handles network input
     */
    public static Integer iThreads = 2;
    public static Integer pThreads = 10;
    /**
     * Handles IO (Crypt)
     */
    public static Integer ioThreads = 4;
    public static final Integer rateLimit = 15;
    public static final Integer rateLimitSleep = 1000;
    public static boolean encryptAllResources = false;
    public static boolean signAllResources = true;
    //
    public static Integer outputProcessorThreads = 6;  // Parallel OutputProcessor threads for CXHELLO/event processing
    public static Long IO_THREAD_SLEEP = 1L;
    public static Long ioSocketSleep = 1L;
    public static Integer ioWriteByteBuffer = 20048;
    public static Integer ioReadByteBuffer = 2048;
    public static Integer ioReverseByteBuffer = 20048;
    //TODO
    public static Integer IO_INPUT_SKIP = 2048;
    public static Integer IO_MAX_INPUT = 10000000;
    public static boolean autoUpdate = true;
    public static boolean revealVersion = true;
    public static boolean supportUnavailableServices = false;
    //WHEN GETTING LOW LEVEL PROTOCOL ERRORS TRY THIS
    public static boolean devMode = true;
    public static Double cxV = 0.1;
    public static boolean DEBUG = false;

    /**
     * When true, incoming stream connections are multiplexed on the main P2P port
     * (SocketWatcher) using the CXST magic-byte prefix. Enables stream sessions for
     * nodes that only have one forwarded port. When false, streams use a dedicated port.
     */
    public static boolean streamMainPortMux = true;

    /**
     * When true, direct TCP/IP is completely disabled for stream sessions.
     * All stream data must flow through the HTTP bridge (WebSocket).
     * Set this on nodes behind a proxied bridge (e.g. Cloudflare) where exposing
     * a direct IP would defeat the purpose of the proxy.
     */
    public static boolean streamBridgeOnly = false;

    // Stream data channel configuration
    /** Minimum buffer size (bytes) a peer will accept for a stream session. */
    public static int streamMinBufferSize = 4096;
    /** Maximum buffer size (bytes) a peer will accept for a stream session. */
    public static int streamMaxBufferSize = 1048576;
    /** Default buffer size (bytes) proposed when opening a stream. */
    public static int streamDefaultBufferSize = 65536;

    /**
     * Enable OUT-LOOP logging - Controls OutputProcessor iteration logging
     * Shows every 100 iterations of the output processor loop
     * Default: false (disabled to reduce log noise)
     */
    public static boolean enableOutLoopLogging = false;

    /**
     * Maximum number of qd (query deduplication) entries held in memory before
     * eviction of entries older than 10 minutes.
     * Default: 500
     */
    public static int maxSeenQd = 500;

    // Peer discovery backoff schedule (persistence thread)
    /** Delay after the first discovery cycle (ms). Default: 30s */
    public static long peerDiscoveryBackoff1Ms = 30_000L;
    /** Delay after the second discovery cycle (ms). Default: 60s */
    public static long peerDiscoveryBackoff2Ms = 60_000L;
    /** Steady-state delay once backoff runs are exhausted (ms). Default: 10 minutes */
    public static long peerDiscoverySteadyMs   = 10 * 60 * 1000L;
}
