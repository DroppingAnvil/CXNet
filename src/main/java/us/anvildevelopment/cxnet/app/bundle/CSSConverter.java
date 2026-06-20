/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app.bundle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort converter from web CSS to JavaFX CSS.
 *
 * JavaFX uses a CSS subset where most visual properties are prefixed with {@code -fx-}.
 * This converter handles the common cases. Properties with no JavaFX equivalent are
 * left as comments so the developer can see what was not converted.
 *
 * For accurate results, authors should provide a hand-authored {@code style-fx.css}
 * in the bundle. This converter is a convenience for simple apps and rapid prototyping.
 *
 * NOTE: Full HTML-to-FXML structural conversion (div to VBox, input to TextField, etc.)
 * is a future milestone. This class handles CSS property mapping only.
 */
public class CSSConverter {

    private static final Map<String, String> PROPERTY_MAP = new LinkedHashMap<>();

    static {
        PROPERTY_MAP.put("background-color",    "-fx-background-color");
        PROPERTY_MAP.put("background-radius",   "-fx-background-radius");
        PROPERTY_MAP.put("background-image",    "-fx-background-image");
        PROPERTY_MAP.put("background-size",     "-fx-background-size");
        PROPERTY_MAP.put("border-color",        "-fx-border-color");
        PROPERTY_MAP.put("border-width",        "-fx-border-width");
        PROPERTY_MAP.put("border-radius",       "-fx-border-radius");
        PROPERTY_MAP.put("border-style",        "-fx-border-style");
        PROPERTY_MAP.put("font-size",           "-fx-font-size");
        PROPERTY_MAP.put("font-family",         "-fx-font-family");
        PROPERTY_MAP.put("font-weight",         "-fx-font-weight");
        PROPERTY_MAP.put("font-style",          "-fx-font-style");
        PROPERTY_MAP.put("text-fill",           "-fx-text-fill");
        PROPERTY_MAP.put("color",               "-fx-text-fill");
        PROPERTY_MAP.put("opacity",             "-fx-opacity");
        PROPERTY_MAP.put("padding",             "-fx-padding");
        PROPERTY_MAP.put("alignment",           "-fx-alignment");
        PROPERTY_MAP.put("spacing",             "-fx-spacing");
        PROPERTY_MAP.put("min-width",           "-fx-min-width");
        PROPERTY_MAP.put("min-height",          "-fx-min-height");
        PROPERTY_MAP.put("max-width",           "-fx-max-width");
        PROPERTY_MAP.put("max-height",          "-fx-max-height");
        PROPERTY_MAP.put("pref-width",          "-fx-pref-width");
        PROPERTY_MAP.put("pref-height",         "-fx-pref-height");
        PROPERTY_MAP.put("cursor",              "-fx-cursor");
        PROPERTY_MAP.put("effect",              "-fx-effect");
        PROPERTY_MAP.put("rotate",              "-fx-rotate");
        PROPERTY_MAP.put("scale-x",             "-fx-scale-x");
        PROPERTY_MAP.put("scale-y",             "-fx-scale-y");
        PROPERTY_MAP.put("translate-x",         "-fx-translate-x");
        PROPERTY_MAP.put("translate-y",         "-fx-translate-y");
    }

    private static final java.util.Set<String> NO_EQUIVALENT = new java.util.LinkedHashSet<>();

    static {
        NO_EQUIVALENT.add("margin");
        NO_EQUIVALENT.add("display");
        NO_EQUIVALENT.add("position");
        NO_EQUIVALENT.add("float");
        NO_EQUIVALENT.add("flex");
        NO_EQUIVALENT.add("grid");
        NO_EQUIVALENT.add("z-index");
        NO_EQUIVALENT.add("box-shadow");
        NO_EQUIVALENT.add("text-decoration");
        NO_EQUIVALENT.add("text-transform");
        NO_EQUIVALENT.add("overflow");
        NO_EQUIVALENT.add("white-space");
        NO_EQUIVALENT.add("content");
        NO_EQUIVALENT.add("transition");
        NO_EQUIVALENT.add("animation");
    }

    private static final Pattern PROPERTY_PATTERN =
            Pattern.compile("(?m)^(\\s*)([-\\w]+)(\\s*:\\s*)(.+?)(\\s*;)");

    /**
     * Convert a web CSS string to a JavaFX CSS string.
     * Properties with known equivalents are renamed. Properties with no JavaFX
     * equivalent are kept as a comment so the developer knows what was dropped.
     *
     * @param webCSS raw CSS string from style.css
     * @return JavaFX CSS string suitable for use as a scene stylesheet
     */
    public static String toJavaFXCSS(String webCSS) {
        if (webCSS == null || webCSS.isEmpty()) return "";

        StringBuffer sb = new StringBuffer();
        Matcher m = PROPERTY_PATTERN.matcher(webCSS);

        while (m.find()) {
            String indent   = m.group(1);
            String property = m.group(2).trim().toLowerCase();
            String colon    = m.group(3);
            String value    = m.group(4).trim();
            String semi     = m.group(5);

            String replacement;
            if (PROPERTY_MAP.containsKey(property)) {
                replacement = indent + PROPERTY_MAP.get(property) + colon + value + semi;
            } else if (NO_EQUIVALENT.contains(property)) {
                replacement = indent + "/* no JavaFX equivalent: " + property + colon.trim() + " " + value + semi.trim() + " */";
            } else if (!property.startsWith("-fx-")) {
                replacement = indent + "-fx-" + property + colon + value + semi;
            } else {
                replacement = m.group(0);
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }

        m.appendTail(sb);
        return sb.toString();
    }
}
