/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh;

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
    public static Integer outputProcessorThreads = 4;  // Parallel OutputProcessor threads for CXHELLO/event processing
    public static Long IO_THREAD_SLEEP = 5L;
    public static Long ioSocketSleep = 5L;
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
}
