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
}
