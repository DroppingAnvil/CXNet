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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for client-side CXApp definitions.
 *
 * Developers subclass this and annotate fields with {@code @CXAppField}.
 * The framework caches these once at registration time. The developer
 * overrides {@link #getTemplate()} to provide the HTML template --
 * {@link #buildHTML()} replaces {@code {{fieldName}}} placeholders with
 * current field values and generates interactive elements for
 * {@code {{invoke:methodName:Label}}} and
 * {@code {{invoke:methodName:Label:arg1Label,arg2Label}}} placeholders.
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
public abstract class CXAppClient {
    private static final Logger log = LoggerFactory.getLogger(CXAppClient.class);

    private String appID;

    /** CXID of the server node this client is connected to. Set at registration or after peer resolution. */
    public volatile String targetCXID;

    // Set by CXAppLoader when loading a bundle; overrides getTemplate() if non-null.
    private String bundledTemplate = null;

    // Built once at registerApp() time
    private final Map<String, Field> fieldCache = new HashMap<>();
    // Caller-side timeouts, declared via @CXAppField(ttlMs) and @CXAppMethod(ttlMs). Read from this
    // client's own annotations because the timeout is enforced here; a caller cannot see the
    // server's annotations. Absent means use NodeConfig.appRequestTimeoutMs.
    private final Map<String, Long> fieldTtlCache  = new HashMap<>();
    private final Map<String, Long> methodTtlCache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Developer API
    // -------------------------------------------------------------------------

    /** Unique identifier for this app. Must match the server's appID. */
    public abstract String getAppID();

    /**
     * Inject the HTML template loaded from a bundle's template.html.
     * Called by {@link us.anvildevelopment.cxnet.app.bundle.CXAppLoader} at bundle load time.
     * If this is called, the result takes precedence over any {@link #getTemplate()} override.
     */
    public final void loadTemplate(String html) {
        this.bundledTemplate = html;
    }

    /**
     * HTML template string defining this app's UI.
     * When loaded from a bundle via {@link us.anvildevelopment.cxnet.app.bundle.CXAppLoader},
     * this is populated from template.html and does not need to be overridden.
     * Inline subclasses (not using bundles) should override this method.
     *
     * <p>Placeholder syntax:
     * <ul>
     *   <li>{@code {{fieldName}}} - replaced with the current value of the named {@code @CXAppField} field.</li>
     *   <li>{@code {{invoke:methodName:ButtonLabel}}} - generates a no-arg action button.</li>
     *   <li>{@code {{invoke:methodName:ButtonLabel:argLabel1,argLabel2}}} - generates a form with labeled inputs.</li>
     * </ul>
     *
     * <p><b>Surface contract:</b> all core functionality (data display, field edits, method invocations)
     * must work through the structured placeholder system above, with no dependency on JavaScript.
     * CXNexus renders templates with JavaScript disabled. Chrome renders the same template and
     * additionally allows JavaScript for visual effects and progressive enhancement only.
     * An app that requires JavaScript for core functionality will break in CXNexus.
     *
     * <p><b>JavaScript source restriction (browser surface):</b> scripts may only be loaded from
     * {@code .js} files packed alongside the app at install time. Scripts must not be embedded
     * inline in the template and must not be sourced from the server or any remote origin.
     * Field values are HTML-escaped by the framework and cannot inject executable content.
     * This restriction exists because field values originate from a remote peer and cannot be
     * fully trusted as safe markup, even over a verified CX connection.
     *
     * <p>Field values substituted into this template are HTML-escaped by the framework.
     * Do not double-escape values you expect to appear as plain text.
     */
    public String getTemplate() {
        return bundledTemplate != null ? bundledTemplate : "";
    }

    // -------------------------------------------------------------------------
    // Framework internals
    // -------------------------------------------------------------------------

    /** Called once by ConnectX.registerApp() to build the field cache. */
    public final void buildCache() {
        this.appID = getAppID();

        // The whole hierarchy is scanned so a shared base client can declare inherited fields and
        // timeouts, matching CXAppServer.buildCache; see that method for why the chain walk is
        // required. Most-derived first, skipping a name already seen, so a subclass wins.
        java.util.Set<String> seenMethods = new java.util.HashSet<>();

        for (Class<?> c = getClass(); c != null && c != CXAppClient.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                CXAppField ann = f.getAnnotation(CXAppField.class);
                if (ann == null || fieldCache.containsKey(f.getName())) continue;
                f.setAccessible(true);
                fieldCache.put(f.getName(), f);
                if (ann.ttlMs() > 0) fieldTtlCache.put(f.getName(), ann.ttlMs());
            }

            // Methods are scanned for their declared timeout only. The stub is never invoked
            // locally: INVOKE runs on the server. Annotating one here is how a caller states how
            // long that call is expected to take, instead of raising the node-wide default for
            // every app.
            //
            // Presence is tracked separately from the timeout map, because a subclass declaring
            // ttlMs = 0 means "use the default" and must still shadow a base class that declared a
            // value. Keying only on the map would let the base value leak through.
            for (Method m : c.getDeclaredMethods()) {
                CXAppMethod ann = m.getAnnotation(CXAppMethod.class);
                if (ann == null || !seenMethods.add(m.getName())) continue;
                if (ann.ttlMs() > 0) methodTtlCache.put(m.getName(), ann.ttlMs());
            }
        }

        log.info("[CXApp] Client '{}' cached {} field(s), {} declared timeout(s)",
                appID, fieldCache.size(), fieldTtlCache.size() + methodTtlCache.size());
    }

    /**
     * Apply field values from an APP_RESPONSE, then return the rendered HTML.
     * Called by the NodeMesh APP_RESPONSE handler or the HTTP bridge after each response.
     */
    public final String applyAndRender(CXAppResponse response) {
        if (response != null && response.success && response.fields != null) {
            for (Map.Entry<String, String> e : response.fields.entrySet()) {
                applyField(e.getKey(), e.getValue());
            }
        }
        return buildHTML();
    }

    /**
     * Render the current field state into HTML using the developer-provided template.
     * Replaces field and invoke placeholders.
     */
    public final String buildHTML() {
        String html = getTemplate();
        if (html == null) return "";

        // Replace field value placeholders: {{fieldName}}
        for (Map.Entry<String, Field> e : fieldCache.entrySet()) {
            try {
                Object val = e.getValue().get(this);
                html = html.replace("{{" + e.getKey() + "}}", escapeHtml(val != null ? String.valueOf(val) : ""));
            } catch (Exception ignored) {}
        }

        // Replace invoke placeholders: {{invoke:method:Label}} or {{invoke:method:Label:arg1,arg2}}
        html = resolveInvokePlaceholders(html);

        return html;
    }

    // -------------------------------------------------------------------------
    // Placeholder resolution
    // -------------------------------------------------------------------------

    private String resolveInvokePlaceholders(String html) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < html.length()) {
            int start = html.indexOf("{{invoke:", i);
            if (start == -1) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, start);

            int end = html.indexOf("}}", start);
            if (end == -1) {
                out.append(html, start, html.length());
                break;
            }

            // Content between {{ and }}
            String inner = html.substring(start + 2, end); // e.g. "invoke:setTarget:Set Target:Host,Port"
            out.append(buildInvokeElement(inner));
            i = end + 2;
        }
        return out.toString();
    }

    /**
     * Parses an "invoke:method:Label" or "invoke:method:Label:arg1,arg2" token
     * and returns an HTML button or form element tagged with data-cxapp-invoke.
     */
    private String buildInvokeElement(String token) {
        // Split on ':' -- limit 4 to keep arg labels together if they contain colons
        String[] parts = token.split(":", 4);
        // parts[0] = "invoke", parts[1] = methodName, parts[2] = label, parts[3] = argLabels (optional)
        if (parts.length < 3) return "<!-- malformed invoke placeholder: " + token + " -->";

        String method    = parts[1].trim();
        String label     = parts[2].trim();
        String argLabels = parts.length == 4 ? parts[3].trim() : "";

        if (argLabels.isEmpty()) {
            // No-arg: plain button
            return "<button data-cxapp-appid=\"" + escapeAttr(appID) + "\" "
                 + "data-cxapp-invoke=\"" + escapeAttr(method) + "\">"
                 + escapeHtml(label) + "</button>";
        }

        // With args: form with labeled inputs
        String[] argNames = argLabels.split(",");
        StringBuilder form = new StringBuilder();
        form.append("<form data-cxapp-appid=\"").append(escapeAttr(appID)).append("\" ")
            .append("data-cxapp-invoke=\"").append(escapeAttr(method)).append("\">");
        for (int idx = 0; idx < argNames.length; idx++) {
            String argLabel = argNames[idx].trim();
            form.append("<input name=\"arg").append(idx).append("\" placeholder=\"")
                .append(escapeAttr(argLabel)).append("\"/>");
        }
        form.append("<button type=\"submit\">").append(escapeHtml(label)).append("</button>");
        form.append("</form>");
        return form.toString();
    }

    // -------------------------------------------------------------------------
    // Calling the server
    // -------------------------------------------------------------------------

    /**
     * Set by ConnectX.registerApp. A client that was never registered cannot send, and says so
     * rather than throwing a NullPointerException out of a future.
     */
    private volatile ConnectX connectX;

    /** Called by ConnectX.registerApp. Not part of the developer API. */
    public final void attach(ConnectX cx) {
        this.connectX = cx;
    }

    /**
     * Read one field from the server.
     *
     * The returned future completes on a CXApp lane, never on the EventProcessor, so chaining
     * {@code thenApply} or {@code thenAccept} onto it cannot stall the mesh. It completes
     * exceptionally with {@link java.util.concurrent.TimeoutException} if the server does not
     * answer within this field's {@code @CXAppField(ttlMs=...)}, or
     * {@code NodeConfig.appRequestTimeoutMs} when the field declares none.
     *
     * A response with {@code success == false} completes the future normally: the server answered,
     * and the answer was a refusal. Inspect {@link CXAppResponse#error}.
     *
     * Field values on this client are applied by the APP_RESPONSE handler before the future
     * completes, so {@link #buildHTML()} already reflects the response by the time a callback runs.
     */
    public final CompletableFuture<CXAppResponse> read(String field) {
        return send("READ", field, null, fieldTtl(field));
    }

    /**
     * Write one field on the server. The value is encoded exactly as the server will decode it;
     * see CXAppCodec.
     */
    public final CompletableFuture<CXAppResponse> write(String field, Object value) {
        String encoded;
        try {
            encoded = CXAppCodec.stringify(value);
        } catch (Exception e) {
            CompletableFuture<CXAppResponse> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
        return send("WRITE", field, new String[]{ encoded }, fieldTtl(field));
    }

    /**
     * Invoke a method on the server.
     *
     * The timeout comes from {@code @CXAppMethod(ttlMs=...)} on this client's own declaration of
     * the method, falling back to {@code NodeConfig.appRequestTimeoutMs}. A client declares a
     * method it intends to call by annotating a same-named stub; the stub is never executed, it
     * exists so the caller can state how long that call is expected to take. See buildCache.
     */
    public final CompletableFuture<CXAppResponse> invoke(String method, Object... args) {
        String[] encoded = null;
        if (args != null && args.length > 0) {
            encoded = new String[args.length];
            try {
                for (int i = 0; i < args.length; i++) encoded[i] = CXAppCodec.stringify(args[i]);
            } catch (Exception e) {
                CompletableFuture<CXAppResponse> f = new CompletableFuture<>();
                f.completeExceptionally(e);
                return f;
            }
        }
        return send("INVOKE", method, encoded, methodTtl(method));
    }

    /** Read every readable field the caller is permitted to see, in one request. */
    public final CompletableFuture<CXAppResponse> refresh() {
        return send("REFRESH", null, null, 0);
    }

    private CompletableFuture<CXAppResponse> send(String op, String target, String[] args, long ttlMs) {
        ConnectX cx = connectX;
        if (cx == null) {
            CompletableFuture<CXAppResponse> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException(
                    "CXAppClient '" + appID + "' is not registered; call ConnectX.registerApp first"));
            return f;
        }
        return cx.sendAppRequest(appID, targetCXID, op, target, args, ttlMs);
    }

    private long fieldTtl(String name) {
        Long ttl = fieldTtlCache.get(name);
        return ttl == null ? 0 : ttl;
    }

    private long methodTtl(String name) {
        Long ttl = methodTtlCache.get(name);
        return ttl == null ? 0 : ttl;
    }

    // -------------------------------------------------------------------------
    // Field update
    // -------------------------------------------------------------------------

    private void applyField(String fieldName, String value) {
        if ("_return".equals(fieldName)) return; // method return value, not a field
        Field f = fieldCache.get(fieldName);
        if (f == null) return;
        try {
            f.set(this, coerce(value, f.getType()));
        } catch (Exception e) {
            log.warn("[CXApp] Client '{}' could not apply field '{}': {}", appID, fieldName, e.getMessage());
        }
    }

    /**
     * Parse a wire value into the field's declared type. The conversion itself lives in
     * CXAppCodec, shared with CXAppServer so the two halves cannot drift apart again.
     *
     * Returns null on failure rather than throwing, because applyField treats one unparseable
     * field as non-fatal to the rest of a REFRESH. Every failure is logged.
     */
    private Object coerce(String value, Class<?> type) {
        if (value == null || value.isEmpty()) return null;
        try {
            return CXAppCodec.coerce(value, type);
        } catch (Exception e) {
            log.warn("[CXApp] Client '{}' type coercion failed for type {}: {}", appID, type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // HTML escaping
    // TODO: promote escapeHtml and escapeAttr to a shared sanitizer utility so
    // CXNexus and other surfaces can reuse them without duplicating this logic.
    // -------------------------------------------------------------------------

    /**
     * Escape a value for insertion anywhere in the developer's template.
     *
     * Quotes are escaped as well as angle brackets. This is used for remote field values at the
     * {@code {{field}}} substitution in buildHTML, and a template decides the surrounding context,
     * so the same value may land in text or inside a quoted attribute. Escaping only {@code &<>} was
     * sufficient for text but allowed a remote value to break out of {@code title="{{name}}"} and
     * add an event handler attribute. Escaping the superset is correct in both contexts.
     */
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }
}
