/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.annotations.CXAppField;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

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

    // Built once at registerApp() time
    private final Map<String, Field> fieldCache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Developer API
    // -------------------------------------------------------------------------

    /** Unique identifier for this app. Must match the server's appID. */
    public abstract String getAppID();

    /**
     * HTML template string. Use {@code {{fieldName}}} to embed field values.
     * Use {@code {{invoke:methodName:ButtonLabel}}} for a no-arg action button.
     * Use {@code {{invoke:methodName:ButtonLabel:argLabel1,argLabel2}}} for a form with labeled inputs.
     * The framework replaces all placeholders during {@link #buildHTML()}.
     */
    public abstract String getTemplate();

    // -------------------------------------------------------------------------
    // Framework internals
    // -------------------------------------------------------------------------

    /** Called once by ConnectX.registerApp() to build the field cache. */
    public final void buildCache() {
        this.appID = getAppID();

        for (Field f : getClass().getDeclaredFields()) {
            if (f.getAnnotation(CXAppField.class) != null) {
                f.setAccessible(true);
                fieldCache.put(f.getName(), f);
            }
        }

        log.info("[CXApp] Client '{}' cached {} field(s)", appID, fieldCache.size());
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
                html = html.replace("{{" + e.getKey() + "}}", val != null ? String.valueOf(val) : "");
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

    private Object coerce(String value, Class<?> type) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (type == String.class)                                 return value;
            if (type == int.class     || type == Integer.class)       return Integer.parseInt(value);
            if (type == long.class    || type == Long.class)          return Long.parseLong(value);
            if (type == boolean.class || type == Boolean.class)       return Boolean.parseBoolean(value);
            if (type == double.class  || type == Double.class)        return Double.parseDouble(value);
            if (type == float.class   || type == Float.class)         return Float.parseFloat(value);
        } catch (Exception e) {
            log.warn("[CXApp] Client '{}' type coercion failed for type {}: {}", appID, type.getSimpleName(), e.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // HTML escaping
    // -------------------------------------------------------------------------

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }
}
