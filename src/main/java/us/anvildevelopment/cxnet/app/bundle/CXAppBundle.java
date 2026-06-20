/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app.bundle;

import us.anvildevelopment.cxnet.app.CXAppClient;
import us.anvildevelopment.cxnet.app.CXAppServer;

import java.io.File;

/**
 * A loaded CXApp bundle. Produced by {@link CXAppLoader#load(File)}.
 *
 * Holds the instantiated server and client (either may be null if the bundle
 * does not include that side), the resolved template HTML, and styling resources
 * for both rendering surfaces.
 *
 * Pass to {@link CXAppLoader#load(File, us.anvildevelopment.cxnet.ConnectX)} to
 * register server and client automatically, or register manually via
 * {@code ConnectX.registerApp()}.
 */
public class CXAppBundle {

    public final CXAppManifest manifest;
    public final File          bundleDir;

    /** Server-side app instance. Null if the bundle has no serverClass. */
    public final CXAppServer server;

    /** Client-side app instance. Null if the bundle has no clientClass. */
    public final CXAppClient client;

    /** Raw HTML template loaded from template.html. Already injected into the client via loadTemplate(). */
    public final String templateHTML;

    /** Raw CSS from style.css for the browser surface. Null if the file is absent. */
    public final String css;

    /**
     * JavaFX CSS for the CXNexus surface.
     * If style-fx.css is present in the bundle it is used directly.
     * Otherwise this is a best-effort auto-conversion of style.css via {@link CSSConverter}.
     * Null if neither source is available.
     *
     * NOTE: Full HTML-to-FXML template structural conversion is a future milestone.
     * The CXNexus WebView renders template.html directly until FXML templates are supported.
     * style-fx.css is applied to the WebView or to surrounding JavaFX chrome (panels, toolbars).
     */
    public final String javaFXCss;

    /** True if app.js is present in the bundle directory. The extension loads it from the bundle path. */
    public final boolean hasJS;

    public CXAppBundle(CXAppManifest manifest, File bundleDir,
                       CXAppServer server, CXAppClient client,
                       String templateHTML, String css, String javaFXCss, boolean hasJS) {
        this.manifest    = manifest;
        this.bundleDir   = bundleDir;
        this.server      = server;
        this.client      = client;
        this.templateHTML = templateHTML;
        this.css         = css;
        this.javaFXCss   = javaFXCss;
        this.hasJS       = hasJS;
    }

    public String getAppID() {
        return manifest != null ? manifest.appID : null;
    }
}
