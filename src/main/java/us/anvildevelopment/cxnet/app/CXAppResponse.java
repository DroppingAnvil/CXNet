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
    public String error;

    public CXAppResponse() {}

    public static CXAppResponse ok(String appID, Map<String, String> fields) {
        CXAppResponse r = new CXAppResponse();
        r.appID   = appID;
        r.success = true;
        r.fields  = fields;
        return r;
    }

    public static CXAppResponse fail(String appID, String error) {
        CXAppResponse r = new CXAppResponse();
        r.appID   = appID;
        r.success = false;
        r.error   = error;
        return r;
    }
}
