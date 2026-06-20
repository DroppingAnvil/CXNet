package us.anvildevelopment.cxnet.io;

public enum JobType {
    WRITE,
    REVERSE,
    NETWORK_READ,
    SIGN_OBJECT,
    DECRYPT,
    BUILD_OUTPUT,
    /**
     * No I/O. Carries already-resolved data (set in o/o1 before being queued) and exists only
     * to run doAfter() on an IOThread instead of inline on whatever thread queued it. Used to
     * move callback execution off single-threaded hot paths like EventProcessor.
     */
    CALLBACK
}
