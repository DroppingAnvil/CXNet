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

    /**
     * Suffix of the per-app diagnostic permission. The full permission is the app's own ID plus
     * this suffix, so granting it on one app says nothing about any other; see
     * {@link #debugPermission()}.
     */
    public static final String DEBUG_PERMISSION_SUFFIX = ".debug";

    /**
     * Permission granting diagnostic detail on failures for this app, for example
     * {@code "RProx.debug"}. A caller holding it is told which of unknown target, denied
     * permission, or unreadable field actually occurred; every other caller receives an
     * undifferentiated {@link CXAppError#FORBIDDEN} so they cannot enumerate an app's fields and
     * methods by comparing replies.
     *
     * It grants detail only. It never grants access: an operation refused without this permission
     * is refused with it too, only more informatively. Grant it to developers working against an
     * app, not as a way to widen what a peer may do.
     */
    public final String debugPermission() {
        return appID + DEBUG_PERMISSION_SUFFIX;
    }

    private String appID;

    // Built once at registerApp() time
    private final Map<String, CachedField>  fieldCache  = new HashMap<>();
    private final Map<String, CachedMethod> methodCache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Developer API
    // -------------------------------------------------------------------------

    /** Unique identifier for this app. Must match the client's appID. */
    public abstract String getAppID();

    /**
     * Whether this app accepts requests from the browser (Chrome extension via loopback HTTP bridge).
     * Override and return false to restrict this app to in-process CXNexus calls only.
     * When false, browser-originated requests are rejected and the extension shows an "Open in CX" prompt.
     * Defaults to true.
     */
    public boolean browserEnabled() { return true; }

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
            return CXAppResponse.fail(appID, CXAppError.MALFORMED_REQUEST);
        }

        if (request.fromBrowser && !browserEnabled()) {
            return CXAppResponse.fail(appID, CXAppError.BROWSER_NOT_ALLOWED);
        }

        try {
            switch (request.op) {
                case "READ":    return handleRead(request, senderCXID, dataContainer);
                case "WRITE":   return handleWrite(request, senderCXID, dataContainer);
                case "INVOKE":  return handleInvoke(request, senderCXID, dataContainer);
                case "REFRESH": return handleRefresh(senderCXID, dataContainer);
                default:
                    return CXAppResponse.fail(appID, CXAppError.UNKNOWN_OP, request.op);
            }
        } catch (Exception e) {
            // The handler's own message is kept out of the response: it is arbitrary developer
            // text and may name internal state. Logged locally instead.
            log.error("[CXApp] '{}' unhandled error on op {}: {}", appID, request.op, e.getMessage());
            return CXAppResponse.fail(appID, CXAppError.HANDLER_ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // Op handlers
    // -------------------------------------------------------------------------

    private CXAppResponse handleRead(CXAppRequest req, String senderCXID, DataContainer dc) throws Exception {
        CachedField cf = fieldCache.get(req.target);
        // Missing, denied and unreadable all collapse to FORBIDDEN unless the caller holds
        // debugPermission(). The permission lives on the field, so there is nothing to check when
        // the field does not exist; answering differently in each case would let an unauthorized
        // caller enumerate field names by comparing replies.
        if (cf == null)
            return refuse("READ", senderCXID, dc, CXAppError.UNKNOWN_FIELD, req.target);
        if (!checkPermission(cf.permission, senderCXID, dc))
            return refuse("READ", senderCXID, dc, CXAppError.PERMISSION_DENIED, req.target);
        if (!cf.readable)
            return refuse("READ", senderCXID, dc, CXAppError.FIELD_NOT_READABLE, req.target);

        Map<String, String> result = new HashMap<>();
        result.put(req.target, stringify(cf.field.get(this)));
        return CXAppResponse.ok(appID, result);
    }

    private CXAppResponse handleWrite(CXAppRequest req, String senderCXID, DataContainer dc) throws Exception {
        CachedField cf = fieldCache.get(req.target);
        // Missing, denied and unwritable collapse to FORBIDDEN without debugPermission(); see handleRead.
        if (cf == null)
            return refuse("WRITE", senderCXID, dc, CXAppError.UNKNOWN_FIELD, req.target);
        if (!checkPermission(cf.permission, senderCXID, dc))
            return refuse("WRITE", senderCXID, dc, CXAppError.PERMISSION_DENIED, req.target);
        if (!cf.writable)
            return refuse("WRITE", senderCXID, dc, CXAppError.FIELD_NOT_WRITABLE, req.target);
        // Past this point the caller is permitted, so a malformed call may be reported plainly.
        if (req.args == null || req.args.length == 0)
            return CXAppResponse.fail(appID, CXAppError.MISSING_ARGUMENT, "WRITE requires args[0]");

        cf.field.set(this, coerce(req.args[0], cf.field.getType()));
        Map<String, String> result = new HashMap<>();
        result.put(req.target, stringify(cf.field.get(this)));
        return CXAppResponse.ok(appID, result);
    }

    private CXAppResponse handleInvoke(CXAppRequest req, String senderCXID, DataContainer dc) throws Exception {
        CachedMethod cm = methodCache.get(req.target);
        // Missing and denied collapse to FORBIDDEN without debugPermission(); see handleRead.
        if (cm == null)
            return refuse("INVOKE", senderCXID, dc, CXAppError.UNKNOWN_METHOD, req.target);
        if (!checkPermission(cm.permission, senderCXID, dc))
            return refuse("INVOKE", senderCXID, dc, CXAppError.PERMISSION_DENIED, req.target);

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

    /**
     * Refuse an operation, disclosing why only to holders of {@link #debugPermission()}.
     *
     * The reason is always logged locally, so the node operator can diagnose a refusal even when
     * the caller was told nothing. This is the single place that decides how much a refusal
     * reveals; callers should never build a FORBIDDEN response directly.
     */
    private CXAppResponse refuse(String op, String senderCXID, DataContainer dc,
                                 CXAppError detail, String target) {
        log.debug("[CXApp] '{}' {} refused for {}: {} ({})", appID, op, senderCXID, detail.name(), target);
        if (checkPermission(debugPermission(), senderCXID, dc)) {
            return CXAppResponse.fail(appID, detail, target);
        }
        return CXAppResponse.fail(appID, CXAppError.FORBIDDEN);
    }

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

    /**
     * Types carried on the wire as their plain text form rather than as cxJSON1. This is the single
     * definition of that set: {@link #coerce} parses exactly these and {@link #stringify} prints
     * exactly these, so the two cannot drift into an asymmetry where a value goes out in one form
     * and is read back as another.
     *
     * Tested against a declared parameter or field type in coerce, which may be primitive, and
     * against a runtime class in stringify, which never is, so both spellings of each scalar are
     * listed.
     */
    private static boolean isScalar(Class<?> type) {
        return type == String.class
            || type == int.class     || type == Integer.class
            || type == long.class    || type == Long.class
            || type == boolean.class || type == Boolean.class
            || type == double.class  || type == Double.class
            || type == float.class   || type == Float.class;
    }

    private Object coerce(String value, Class<?> type) throws Exception {
        if (value == null)                                        return null;
        if (type == String.class)                                 return value;
        if (type == int.class     || type == Integer.class)       return Integer.parseInt(value);
        if (type == long.class    || type == Long.class)          return Long.parseLong(value);
        if (type == boolean.class || type == Boolean.class)       return Boolean.parseBoolean(value);
        if (type == double.class  || type == Double.class)        return Double.parseDouble(value);
        if (type == float.class   || type == Float.class)         return Float.parseFloat(value);
        // Everything else is cxJSON1, which is the form stringify writes it in; see isScalar.
        return ConnectX.deserialize("cxJSON1", value, type);
    }

    /**
     * Render a value for the response fields map, in the same form {@link #coerce} reads it back.
     *
     * Scalars go out as plain text; everything else goes out as cxJSON1. This previously used
     * String.valueOf for every type, so a collection or POJO field was sent as its toString -- a
     * Map as {@code {a=b}}, which is not cxJSON1 and cannot be parsed back by any client. READ,
     * WRITE confirmation and REFRESH all returned that silently, with success set.
     *
     * A value that cannot be serialized throws rather than falling back to toString. Emitting an
     * unparseable string on success is the failure being fixed here, so it is not reintroduced as a
     * fallback; handle() turns the throw into HANDLER_ERROR and logs it against the app.
     */
    private String stringify(Object value) throws Exception {
        if (value == null) return "";
        if (isScalar(value.getClass())) return String.valueOf(value);
        return ConnectX.serialize("cxJSON1", value);
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
