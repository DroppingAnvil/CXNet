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
 * Marks a field on a CXAppServer or CXAppClient as network-accessible.
 * Only annotated fields are exposed -- all others are invisible to the CXApp framework.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface CXAppField {
    /** If true, the field value can be read by remote clients via READ requests. */
    boolean readable() default true;
    /** If true, the field value can be set by remote clients via WRITE requests. */
    boolean writable() default false;
    /** Required permission string. Empty string means no permission required. */
    String permission() default "";
    /**
     * How long a caller waits for the response to a READ or WRITE of this field, in milliseconds.
     * Zero means use {@code NodeConfig.appRequestTimeoutMs}.
     *
     * Read from the CXAppClient's own copy of the field, not from the server's, because the
     * timeout is enforced by the waiting caller and a caller cannot see the server's annotations.
     * Declaring it on the server side is harmless but has no effect.
     */
    long ttlMs() default 0;
}
