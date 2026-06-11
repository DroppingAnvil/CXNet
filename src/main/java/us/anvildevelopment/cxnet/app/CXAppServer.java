/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.annotations.CXAppField;
import us.anvildevelopment.cxnet.annotations.CXAppMethod;
import us.anvildevelopment.cxnet.edge.DataContainer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for server-side CXApp definitions.
 *
 * Developers subclass this and annotate fields with {@code @CXAppField} and
 * methods with {@code @CXAppMethod}. The framework scans and caches these once
 * at registration time via {@link #buildCache()} -- all subsequent request
 * handling is purely map-lookup + cached reflection invocation.
 *
 * Methods may be private. The framework calls {@code setAccessible(true)} on
 * them at cache-build time so the hot path never touches reflection metadata.
 *
 * -------------------------------------------------------------------------
 * OFFICIAL APP REGISTRATION
 * -------------------------------------------------------------------------
 * Official CXApp registration will be available at AnvilDevelopment.US.
 * To keep the network truly open, app IDs are NOT enforced at the protocol
 * level. Loading an unofficial or unregistered CXApp carries risk -- an app ID
 * collision with a malicious or poorly written app can result in data
 * corruption or unintended functionality. Only load CXApps from sources you
 * trust.
 * -------------------------------------------------------------------------
 */
public abstract class CXAppServer {
    private static final Logger log = LoggerFactory.getLogger(CXAppServer.class);

    private String appID;

    // Built once at registerApp() time
    private final Map<String, CachedField>  fieldCache  = new HashMap<>();
    private final Map<String, CachedMethod> methodCache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Developer API
    // -------------------------------------------------------------------------

    /** Unique identifier for this app. Must match the client's appID. */
    public abstract String getAppID();

    // -------------------------------------------------------------------------
    // Framework internals
    // -------------------------------------------------------------------------

    /** Called once by ConnectX.registerApp() to build reflection caches. */
    public final void buildCache() {
        this.appID = getAppID();

        for (Field f : getClass().getDeclaredFields()) {
            CXAppField ann = f.getAnnotation(CXAppField.class);
            if (ann != null) {
                f.setAccessible(true);
                CachedField cf = new CachedField();
                cf.field      = f;
                cf.permission = ann.permission().isEmpty() ? null : ann.permission();
                cf.readable   = ann.readable();
                cf.writable   = ann.writable();
                fieldCache.put(f.getName(), cf);
            }
        }

        for (Method m : getClass().getDeclaredMethods()) {
            CXAppMethod ann = m.getAnnotation(CXAppMethod.class);
            if (ann != null) {
                m.setAccessible(true);
                CachedMethod cm = new CachedMethod();
                cm.method     = m;
                cm.permission = ann.permission().isEmpty() ? null : ann.permission();
                methodCache.put(m.getName(), cm);
            }
        }

        log.info("[CXApp] Server '{}' cached {} field(s), {} method(s)",
                appID, fieldCache.size(), methodCache.size());
    }

    /**
     * Handle an incoming APP_REQUEST. Called by the NodeMesh APP_REQUEST handler.
     *
     * @param request      deserialized request payload
     * @param senderCXID   CXID of the requesting peer (from NetworkEvent path)
     * @param dataContainer local DataContainer for permission checks
     * @return response to send back as APP_RESPONSE payload
     */
    public final CXAppResponse handle(CXAppRequest request, String senderCXID, DataContainer dataContainer) {
        if (request == null || request.op == null) {
            return CXAppResponse.fail(appID, "Malformed request");
        }

        try {
            switch (request.op) {
                case "READ":    return handleRead(request, senderCXID, dataContainer);
                case "WRITE":   return handleWrite(request, senderCXID, dataContainer);
                case "INVOKE":  return handleInvoke(request, senderCXID, dataContainer);
                case "REFRESH": return handleRefresh(senderCXID, dataContainer);
                default:
                    return CXAppResponse.fail(appID, "Unknown op: " + request.op);
            }
        } catch (Exception e) {
            log.error("[CXApp] '{}' unhandled error on op {}: {}", appID, request.op, e.getMessage());
            return CXAppResponse.fail(appID, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Op handlers
    // -------------------------------------------------------------------------

    private CXAppResponse handleRead(CXAppRequest req, String senderCXID, DataContainer dc) throws Exception {
        CachedField cf = fieldCache.get(req.target);
        if (cf == null)   return CXAppResponse.fail(appID, "Unknown field: " + req.target);
        if (!cf.readable) return CXAppResponse.fail(appID, "Field not readable: " + req.target);
        if (!checkPermission(cf.permission, senderCXID, dc))
            return CXAppResponse.fail(appID, "Forbidden");

        Map<String, String> result = new HashMap<>();
        result.put(req.target, stringify(cf.field.get(this)));
        return CXAppResponse.ok(appID, result);
    }

    private CXAppResponse handleWrite(CXAppRequest req, String senderCXID, DataContainer dc) throws Exception {
        CachedField cf = fieldCache.get(req.target);
        if (cf == null)   return CXAppResponse.fail(appID, "Unknown field: " + req.target);
        if (!cf.writable) return CXAppResponse.fail(appID, "Field not writable: " + req.target);
        if (!checkPermission(cf.permission, senderCXID, dc))
            return CXAppResponse.fail(appID, "Forbidden");
        if (req.args == null || req.args.length == 0)
            return CXAppResponse.fail(appID, "WRITE requires args[0]");

        cf.field.set(this, coerce(req.args[0], cf.field.getType()));
        Map<String, String> result = new HashMap<>();
        result.put(req.target, stringify(cf.field.get(this)));
        return CXAppResponse.ok(appID, result);
    }

    private CXAppResponse handleInvoke(CXAppRequest req, String senderCXID, DataContainer dc) throws Exception {
        CachedMethod cm = methodCache.get(req.target);
        if (cm == null) return CXAppResponse.fail(appID, "Unknown method: " + req.target);
        if (!checkPermission(cm.permission, senderCXID, dc))
            return CXAppResponse.fail(appID, "Forbidden");

        Object[] coercedArgs = coerceArgs(req.args, cm.method.getParameterTypes());
        Object returnVal = cm.method.invoke(this, coercedArgs);

        Map<String, String> result = new HashMap<>();
        if (returnVal != null) result.put("_return", stringify(returnVal));
        return CXAppResponse.ok(appID, result);
    }

    private CXAppResponse handleRefresh(String senderCXID, DataContainer dc) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, CachedField> e : fieldCache.entrySet()) {
            CachedField cf = e.getValue();
            if (!cf.readable) continue;
            if (!checkPermission(cf.permission, senderCXID, dc)) continue;
            result.put(e.getKey(), stringify(cf.field.get(this)));
        }
        return CXAppResponse.ok(appID, result);
    }

    // -------------------------------------------------------------------------
    // Permission check
    // -------------------------------------------------------------------------

    private boolean checkPermission(String permission, String senderCXID, DataContainer dc) {
        if (permission == null) return true;
        if (dc == null || dc.cxidAppPermissions == null) return false;
        return dc.cxidAppPermissions.allowed(senderCXID, permission);
    }

    // -------------------------------------------------------------------------
    // Type coercion
    // -------------------------------------------------------------------------

    private Object[] coerceArgs(String[] args, Class<?>[] types) throws Exception {
        if (types.length == 0) return new Object[0];
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = coerce(args != null && i < args.length ? args[i] : null, types[i]);
        }
        return out;
    }

    private Object coerce(String value, Class<?> type) throws Exception {
        if (value == null)                                        return null;
        if (type == String.class)                                 return value;
        if (type == int.class     || type == Integer.class)       return Integer.parseInt(value);
        if (type == long.class    || type == Long.class)          return Long.parseLong(value);
        if (type == boolean.class || type == Boolean.class)       return Boolean.parseBoolean(value);
        if (type == double.class  || type == Double.class)        return Double.parseDouble(value);
        if (type == float.class   || type == Float.class)         return Float.parseFloat(value);
        // POJO: deserialize via cxJSON1
        return ConnectX.deserialize("cxJSON1", value, type);
    }

    private String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // -------------------------------------------------------------------------
    // Cache holder types
    // -------------------------------------------------------------------------

    private static class CachedField {
        Field   field;
        String  permission;
        boolean readable;
        boolean writable;
    }

    private static class CachedMethod {
        Method method;
        String permission;
    }
}
