/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

/**
 * CXApps: human-facing applications built on the CX network.
 *
 * <h2>Origin and Purpose</h2>
 * <p>CXNet began as a way to run web applications across servers without trusting
 * the existing security models, which either required expensive third-party infrastructure,
 * key hand-offs, or inherently insecure operations. The core goal became a global
 * authentication system for servers and clients alike: symmetric, easy to develop against,
 * and flexible enough for applications not yet conceived. The entire stack from encryption
 * to transport to identity is handled in one codebase, programmatically, with no external
 * dependencies on trust brokers or certificate authorities outside the CX ecosystem.
 *
 * <h2>Why CXApps</h2>
 * <p>CXNet was initially designed for programmatic, machine-to-machine communication.
 * CXApps extend the network to serve humans directly, rendering interactive UI in a
 * browser or native surface while keeping all logic, identity, and permissions on the
 * CX network. This has required significant design thought around the boundary between
 * what is safe to expose and what must remain protected.
 *
 * <h2>The Core Tradeoff: Security vs Developer Flexibility</h2>
 * <p>The central design tension is security versus flexibility for developers. Compromising
 * security in this system is equivalent to compromising the entire system's purpose, so
 * rather than weakening the security posture to accommodate flexibility, the surface is
 * split and the decision is left to the app developer.
 *
 * <h2>Two Rendering Surfaces</h2>
 * <ul>
 *   <li><b>CXNexus (secure surface):</b> Apps with {@link us.anvildevelopment.cxnet.app.CXAppServer#browserEnabled()}
 *       returning {@code false} are restricted to CXNexus, the official desktop client which
 *       embeds the ConnectX node in-process. CXNexus renders HTML with JavaScript disabled.
 *       This surface is appropriate for pages that edit critical or authenticated data, or
 *       any case where the developer determines that browser-level attack surface is unacceptable.</li>
 *   <li><b>Browser (open surface):</b> Apps with {@code browserEnabled()} returning {@code true}
 *       (the default) may be rendered in the browser via the Chrome extension. JavaScript is
 *       permitted in this surface, but with a strict constraint: scripts may only be loaded
 *       from {@code .js} files packed alongside the app at install time. Scripts may not be
 *       provided by the server or injected through field values. This constraint exists because
 *       field values originate from a remote peer and cannot be considered fully trusted, even
 *       over a verified CX connection. All field values substituted into templates are
 *       HTML-escaped by the framework before rendering.</li>
 * </ul>
 *
 * <h2>Design Principle for App Developers</h2>
 * <p>Core functionality (data display, field edits, method invocations) must work through the
 * structured placeholder and invoke system with no dependency on JavaScript. CXNexus renders
 * with JavaScript disabled. JavaScript in the browser surface is for visual effects and
 * progressive enhancement only. An app that requires JavaScript for core functionality will
 * break in CXNexus.
 *
 * <h2>App Registration</h2>
 * <p>Official CXApp registration will be available at AnvilDevelopment.US. App IDs are not
 * enforced at the protocol level to keep the network open. Loading an unregistered or
 * unofficial CXApp carries risk: an app ID collision with a malicious or poorly written app
 * can result in unintended behavior. Only load CXApps from sources you trust.
 *
 * @see us.anvildevelopment.cxnet.app.CXAppServer
 * @see us.anvildevelopment.cxnet.app.CXAppClient
 */
package us.anvildevelopment.cxnet.app;
