/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app.bundle;

/**
 * Deserialized form of a CXApp bundle's manifest.json.
 *
 * manifest.json example:
 * <pre>
 * {
 *   "appID":       "rprox",
 *   "version":     "1.0.0",
 *   "serverClass": "us.anvildevelopment.rprox.RProxAppServer",
 *   "clientClass": "us.anvildevelopment.rprox.RProxAppClient",
 *   "minCXVersion": "0.4.3"
 * }
 * </pre>
 *
 * serverClass and clientClass are both optional. A bundle can be server-only
 * (deployed on a data node with no local UI) or client-only (a UI shell that
 * connects to a remote server app).
 */
public class CXAppManifest {
    public String appID;
    public String version;
    /** Fully-qualified class name of the CXAppServer subclass. Null if server-only deployment is not included. */
    public String serverClass;
    /** Fully-qualified class name of the CXAppClient subclass. Null if this bundle has no local UI. */
    public String clientClass;
    /** Minimum ConnectX version required. Informational only, not enforced at load time. */
    public String minCXVersion;

    public CXAppManifest() {}
}
