/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

import java.util.Map;

/**
 * Wire format for APP_RESPONSE events returned by a CXAppServer.
 *
 * fields map contents by op:
 *   READ    -- single entry: {fieldName -> value}
 *   WRITE   -- single entry: {fieldName -> confirmedValue}
 *   INVOKE  -- single entry: {"_return" -> serializedReturnValue} or empty if void
 *   REFRESH -- all @CXAppField-annotated fields: {fieldName -> value, ...}
 *
 * The client knows the expected types from its own class definition and
 * deserializes field values accordingly.
 */
public class CXAppResponse {
    public String appID;
    public boolean success;
    public Map<String, String> fields;
    /** Stable machine code, normally a {@link CXAppError} name. Branch on this, not on message. */
    public String error;
    /** Human-readable explanation of error. Free to be reworded; never branch on it. */
    public String message;

    public CXAppResponse() {}

    public static CXAppResponse ok(String appID, Map<String, String> fields) {
        CXAppResponse r = new CXAppResponse();
        r.appID   = appID;
        r.success = true;
        r.fields  = fields;
        return r;
    }

    public static CXAppResponse fail(String appID, CXAppError error) {
        CXAppResponse r = new CXAppResponse();
        r.appID   = appID;
        r.success = false;
        r.error   = error.code();
        r.message = error.message();
        return r;
    }

    /**
     * Failure carrying extra context in the message only. The detail never reaches the wire code,
     * so it must not be used to describe anything a caller should branch on, and must not be used
     * with FORBIDDEN, whose whole purpose is to reveal nothing about why it was refused.
     */
    public static CXAppResponse fail(String appID, CXAppError error, String detail) {
        CXAppResponse r = fail(appID, error);
        if (detail != null && !detail.isEmpty()) r.message = error.message() + ": " + detail;
        return r;
    }

    /**
     * Raw-string failure. Retained for callers not yet migrated to {@link CXAppError}; leaves
     * message null. Prefer the enum overloads.
     */
    public static CXAppResponse fail(String appID, String error) {
        CXAppResponse r = new CXAppResponse();
        r.appID   = appID;
        r.success = false;
        r.error   = error;
        return r;
    }
}
