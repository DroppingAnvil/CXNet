/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.app.CXAppClient;
import us.anvildevelopment.cxnet.app.CXAppServer;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Loads a CXApp bundle from a directory and optionally registers it with a ConnectX node.
 *
 * A bundle is a directory (conventionally named {@code appid.cxapp}) containing:
 * <pre>
 *   manifest.json     - app metadata and class names
 *   app.jar           - compiled CXAppServer and/or CXAppClient subclasses
 *   template.html     - HTML template (injected into the client via loadTemplate())
 *   style.css         - browser surface styles (optional)
 *   style-fx.css      - JavaFX CSS (optional; auto-generated from style.css if absent)
 *   app.js            - browser surface JS (optional, packed at install time)
 * </pre>
 *
 * SECURITY: app.jar runs as trusted code in the same JVM with full process access,
 * including the node's signing key. Only load bundles from sources you trust.
 * Official bundles are registered at AnvilDevelopment.US.
 *
 * @see CXAppBundle
 * @see CXAppManifest
 */
public class CXAppLoader {

    private static final Logger log = LoggerFactory.getLogger(CXAppLoader.class);

    private static final String MANIFEST   = "manifest.json";
    private static final String JAR        = "app.jar";
    private static final String TEMPLATE   = "template.html";
    private static final String CSS        = "style.css";
    private static final String CSS_FX     = "style-fx.css";
    private static final String JS         = "app.js";

    /**
     * Load a CXApp bundle from a directory.
     * Reflection caches are built at load time. Neither server nor client is registered
     * with a ConnectX node; call {@link ConnectX#loadApp(File)} to do both in one step.
     *
     * @param bundleDir the bundle directory (e.g. {@code ./apps/rprox.cxapp})
     * @return loaded bundle
     * @throws Exception if the manifest is missing, the JAR cannot be loaded, or a
     *                   declared class cannot be instantiated
     */
    public static CXAppBundle load(File bundleDir) throws Exception {
        if (bundleDir == null || !bundleDir.isDirectory()) {
            throw new IllegalArgumentException("Bundle path is not a directory: " + bundleDir);
        }

        File manifestFile = new File(bundleDir, MANIFEST);
        if (!manifestFile.exists()) {
            throw new IllegalStateException("Missing manifest.json in bundle: " + bundleDir);
        }

        CXAppManifest manifest = (CXAppManifest) ConnectX.deserialize(
                "cxJSON1",
                new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8),
                CXAppManifest.class);

        if (manifest.appID == null || manifest.appID.isEmpty()) {
            throw new IllegalStateException("manifest.json missing appID in: " + bundleDir);
        }

        File jarFile = new File(bundleDir, JAR);
        if (!jarFile.exists()) {
            throw new IllegalStateException("Missing app.jar in bundle: " + bundleDir);
        }

        URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{jarFile.toURI().toURL()},
                CXAppLoader.class.getClassLoader());

        CXAppServer server = null;
        if (manifest.serverClass != null && !manifest.serverClass.isEmpty()) {
            Class<?> cls = classLoader.loadClass(manifest.serverClass);
            server = (CXAppServer) cls.getDeclaredConstructor().newInstance();
            server.buildCache();
            log.info("[CXApp] Loaded server '{}' from {}", manifest.appID, bundleDir.getName());
        }

        String templateHTML = null;
        CXAppClient client = null;
        if (manifest.clientClass != null && !manifest.clientClass.isEmpty()) {
            Class<?> cls = classLoader.loadClass(manifest.clientClass);
            client = (CXAppClient) cls.getDeclaredConstructor().newInstance();

            File templateFile = new File(bundleDir, TEMPLATE);
            if (templateFile.exists()) {
                templateHTML = new String(Files.readAllBytes(templateFile.toPath()), StandardCharsets.UTF_8);
                client.loadTemplate(templateHTML);
            }

            client.buildCache();
            log.info("[CXApp] Loaded client '{}' from {}", manifest.appID, bundleDir.getName());
        }

        String css = readFile(bundleDir, CSS);
        String javaFXCss = readFile(bundleDir, CSS_FX);
        if (javaFXCss == null && css != null) {
            javaFXCss = CSSConverter.toJavaFXCSS(css);
            log.debug("[CXApp] Auto-converted style.css to JavaFX CSS for '{}'", manifest.appID);
        }

        boolean hasJS = new File(bundleDir, JS).exists();

        return new CXAppBundle(manifest, bundleDir, server, client, templateHTML, css, javaFXCss, hasJS);
    }

    /**
     * Load a CXApp bundle and register its server and client with the given ConnectX node.
     * Equivalent to calling {@link #load(File)} then
     * {@link ConnectX#registerApp(CXAppServer)} and {@link ConnectX#registerApp(CXAppClient)}.
     *
     * @param bundleDir  the bundle directory
     * @param connectX   the local ConnectX node to register the app with
     * @return loaded bundle
     */
    public static CXAppBundle load(File bundleDir, ConnectX connectX) throws Exception {
        CXAppBundle bundle = load(bundleDir);
        if (bundle.server != null) connectX.registerApp(bundle.server);
        if (bundle.client != null) connectX.registerApp(bundle.client);
        log.info("[CXApp] Registered bundle '{}' with ConnectX node", bundle.getAppID());
        return bundle;
    }

    private static String readFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[CXApp] Could not read {} from bundle: {}", name, e.getMessage());
            return null;
        }
    }
}
