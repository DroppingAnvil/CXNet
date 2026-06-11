/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

/**
 * Wire format for APP_REQUEST events sent by a CXAppClient to a CXAppServer.
 * Carried as the payload of a NetworkEvent -- CX-level auth (signing/verification)
 * is handled by the standard NodeMesh pipeline, not by this class.
 *
 * op values:
 *   READ    -- read a single field;          target = field name;   args = null
 *   WRITE   -- set a single field;           target = field name;   args[0] = new value
 *   INVOKE  -- call a method;               target = method name;  args = method parameters (all as strings)
 *   REFRESH -- read all fields at once;     target = null;         args = null
 */
public class CXAppRequest {
    public String appID;
    public String op;
    public String target;
    public String[] args;

    public CXAppRequest() {}

    public CXAppRequest(String appID, String op, String target, String[] args) {
        this.appID  = appID;
        this.op     = op;
        this.target = target;
        this.args   = args;
    }
}
