/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on a CXAppServer as network-invokable via INVOKE requests.
 * Methods may be private -- the framework uses reflection to invoke them.
 * Only annotated methods are reachable over the network.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CXAppMethod {
    /** Required permission string. Empty string means no permission required. */
    String permission() default "";
    /**
     * How long a caller waits for the response to an INVOKE of this method, in milliseconds.
     * Zero means use {@code NodeConfig.appRequestTimeoutMs}. Set it on a method that is expected
     * to be slow, rather than raising the node-wide default for every app.
     *
     * Read from the CXAppClient's declaration of the method, not the server's, because the timeout
     * is enforced by the waiting caller. A client declares a method it intends to call by
     * annotating a same-named stub; see CXAppClient.buildCache.
     */
    long ttlMs() default 0;
}
