# Changelog

## v0.4.3

### Network state persistence fixes

`DataContainer` had two public no-arg utility methods (`getAllLocalPeerAddresses`, `getLocalPeerCount`) that Jackson's default-typing serializer treated as typed properties. On load, Jackson found these properties but had no setter and threw a "setterless typed deser" exception, causing `loadDataContainer` to silently create a blank container. `watchedNetworks` was therefore always empty on restart and no non-CXNET network was ever restored. Both methods renamed to non-getter form.

`restoreJoinedNetworks` was reading `networks/<networkID>/seed.cxn` as raw JSON. These are PGP-signed blobs -- the method now strips the signature before deserializing. Disk-resident seeds are trusted implicitly.

`signAndPublishNetworkSeed("CXNET")` now also overwrites `cxnet-bootstrap.cxn`. Previously only `seeds/<uuid>.cxn` was written, so networks registered via NETEPOCH after first boot were lost on restart.

### Seed trust: backendSet signature required

Dynamic seeds in `SEED_RESPONSE` consensus are now verified against EPOCH + CXNET `backendSet` + target network `backendSet` (if loaded). Unsigned dynamic seeds are rejected with a "manual import required" log. `ConnectX.applyBackendSignedSeed` implements the multi-key verification. EPOCH's own signed blob still applies immediately in `SEED_RESPONSE` without waiting for consensus.

### EPOCH peer directory bootstrap

`CryptProvider.getNmiPublicKey()` added (default null); `PainlessCryptProvider` overrides it. `requestSeedFromEpoch` now populates EPOCH's stub node with the NMI public key so it passes `Node.validate` instead of producing a spurious null-publicKey warning on every bootstrap.

### Session ID (sid) on NetworkEvent

`NetworkEvent.sid` -- a UUID set automatically by `EventBuilder`. Response handlers in NodeMesh echo the request's `sid` back via `EventBuilder.withSid()`. Correlates request-response pairs in logs. Dispatch logic not yet implemented.

## Unreleased

### CXApp system

Introduces the CXApp framework: a human-facing application layer built on the CX event pipeline. Apps expose interactive HTML UI driven by CX network data, with identity, permissions, and transport handled entirely by the existing CX infrastructure.

**Architecture**

`CXAppServer` (abstract) runs on the data-owning node. Fields annotated with `@CXAppField` and methods annotated with `@CXAppMethod` are cached via reflection at registration time (`buildCache()`). Incoming `APP_REQUEST` events are dispatched to `handle()` which routes to one of four ops: `READ`, `WRITE`, `INVOKE`, `REFRESH`.

`CXAppClient` (abstract) runs on the user's local node. Holds an HTML template via `getTemplate()`. Receives `APP_RESPONSE` field values, substitutes them locally via `buildHTML()`, and returns rendered HTML. No HTML ever crosses the CX wire.

`CXAppRequest` and `CXAppResponse` are the wire types, serialized as cxJSON1 and carried as `APP_REQUEST` / `APP_RESPONSE` event payloads through the standard signed NodeMesh pipeline.

**Permissions**

Field and method access is gated per `@CXAppField(permission="...")` and `@CXAppMethod(permission="...")`. Permissions are stored in `DataContainer.cxidAppPermissions` (a `BasicPermissionContainer`), machine-scoped. `REFRESH` silently omits fields the caller lacks permission to read.

```java
connectX.grantCXIDPermission(peerCXID, "admin", 100);
connectX.revokeCXIDPermission(peerCXID, "admin", 100);
```

Note: `BasicPermissionContainer.addEntry()` has two bugs in the current Util1 release (containsKey guard silently drops new CXIDs; inner map key uses `id` instead of `e.getName()`). `grantCXIDPermission` uses `permissionSet.computeIfAbsent().put()` directly as a workaround. A TODO is left for when Util1 is fixed.

**Browser surface and security split**

`CXAppServer.browserEnabled()` (default `true`) controls whether a browser extension may load the app. When `false`, browser requests return `BROWSER_NOT_ALLOWED` and the extension shows an "Open in CX" prompt.

`CXAppRequest.fromBrowser` is set `true` by the loopback HTTP bridge on every browser-originated request. It is a client-managed policy flag with no cryptographic enforcement. A client modified against spec can set it arbitrarily; that is considered out of scope.

**Injection fix**

`CXAppClient.buildHTML()` previously substituted field values raw into the template string. A malicious remote peer could return a field value containing markup or script that would execute in any rendering surface. Field values are now HTML-escaped via `escapeHtml()` before substitution. A TODO notes that `escapeHtml` and `escapeAttr` should be promoted to a shared sanitizer utility for reuse in CXNexus.

**Chrome extension bridge**

`HTTPBridgeProvider` gains an internal `AppServlet` (second Jetty server bound to `127.0.0.1` only, started via `connectX.startAppServer(port)`). The servlet enforces loopback at the OS level and applies an `isLoopback()` check as defense-in-depth.

Per-tab session isolation: each `GET /app/{appID}` generates a UUID session token returned as `X-CXApp-Session` response header. The session maps `{appID, targetCXID}` in `appSessions` (`ConcurrentHashMap`). Every `POST /app/{appID}` must supply the token to look up its session. Multiple tabs for the same app are fully isolated.

`fireAndWait()` builds a `CXAppRequest` with `fromBrowser=true`, fires it as an `APP_REQUEST` event, and blocks on a `LinkedBlockingQueue<String>` keyed by the event sid. The NodeMesh `APP_RESPONSE` handler calls `HTTPBridgeProvider.deliverAppHTML(sid, html)` to unblock it after `CXAppClient.applyAndRender()` renders the HTML locally.

**EventType additions**

`APP_REQUEST(60_000, 1_048_576, 5)` and `APP_RESPONSE(60_000, 1_048_576, 5)` added to `EventType`.

**Unit tests**

`CXAppUnitTest` (JUnit 5, 19 tests): READ, WRITE, WRITE-rejected, WRITE-missing-args, INVOKE with arg, INVOKE void, INVOKE unknown, REFRESH unpermissioned, REFRESH admin, permission READ denied, READ allowed, INVOKE denied, INVOKE allowed, revoke by allow=false, buildHTML field placeholders, button generation, form generation, no leftover placeholders. All 19 pass. Added to Surefire includes alongside `MultiPeerTest`.

**Documentation**

`package-info.java` added for `us.anvildevelopment.cxnet.app` covering origin, design rationale, and the security/flexibility tradeoff. `getTemplate()` Javadoc updated with the surface contract and JS-from-file-only restriction. `README.md` and `CX-PROTOCOL.md` updated with the CXApp spec, two-surface security posture, session model, wire protocol table, and app registration policy.

### CXApp handler dispatch: off the EventProcessor thread

App handlers ran inline on `EventProcessor`. `NodeMesh` starts exactly one such thread (`NodeMesh:107`); it loops on `in.processEvent()`, which reaches `fireEvent`, whose `APP_REQUEST` case called `appServer.handle()` directly and only then built and queued the `APP_RESPONSE`. There was no executor anywhere on that path.

`@CXAppMethod` bodies are arbitrary developer code, so any handler that blocked stalled every event on the node, not just its own app: chat, peer discovery, seed consensus and block exchange all stopped until it returned. Nothing in the API surface said so. `CXAppServer` reads like a request handler and every comparable Java framework dispatches on a pool, so the design invited exactly the code it could not tolerate.

**Dispatcher**

`CXAppDispatcher` runs handlers on a shared bounded pool, but each `appID` owns a lane drained one task at a time. Handlers for a given app therefore never run concurrently and existing `CXAppServer` implementations keep the serialization they were written against; no locking is required of app authors. Because a lane runs a single task at a time, one app can never occupy more than one pool thread, so a blocked handler stalls only its own lane.

The drainer re-submits itself to the pool between tasks rather than looping, so an app with a backlog returns its thread between tasks and cannot starve other apps.

Lanes are never removed. `submit` is only reached for a registered app, so the map is bounded by the number of registered apps; removing a lane would race a concurrent submitter holding the same reference and strand that task.

Backpressure is explicit: when a lane's queue is full the submission is rejected and the caller answers `BUSY` on the request's sid, rather than dropping the request and leaving the requester waiting. Handlers exceeding `appHandlerWarnMs` are logged, which turns the "never block" expectation into something observable instead of documentation.

**Configuration**

`NodeConfig.appThreads` (4), `NodeConfig.appMaxQueuedPerApp` (32), `NodeConfig.appHandlerWarnMs` (1000; 0 disables), alongside the existing `ioThreads` and `outputProcessorThreads`.

**Why a dedicated pool**

Neither existing pool could safely take arbitrary code. `IOThread` is the inbound path (socket reads, `stripSignature`, `verifyAndStrip`, `processNetworkInput`), so blocking it stops the node receiving rather than dispatching, a wider blast radius than the original bug. `OutputProcessor` is the send path, and blocking it strands every outbound event including the `APP_RESPONSE` being delivered. Untrusted code sharing a pool with core protocol work makes pool saturation indistinguishable from node failure.

**Correlation and ordering**

No new correlation mechanism was added. The response is queued with `.withSid(ne.sid).toPeer(nc.iD)`, reusing the existing sid echo. Responses for a given app remain ordered because the lane is serialized; across apps they may now complete out of order relative to arrival, which sid correlation already accommodates.

Queueing from a pool thread is safe and introduces no new exposure: `EventBuilder.queue()` performs no crypto, wrapping the builder in an `IOJob` and adding it to `connectX.jobQueue` under `synchronized`. Signing, encryption, the `connectX.self` read and the path mutation all happen later in `execute()` on IOThread, exactly as for every other caller. IOThread, RetryProcessor and job-completion callbacks already queue from non-EventProcessor threads.

**Known gaps, not addressed here**

`DataContainer` remains fully reachable by handlers, which is deliberate: CXNET is embedded in host applications and apps are expected to interact with the JVM. Some of its collections are not thread-safe (`LAN` is a `HashMap`, `waitingAddresses` an `ArrayList`, `watchedNetworks` a `HashSet`) and were safe only because `EventProcessor` was single-threaded. A handler mutating those from a pool thread is a genuine race. This predates the change for plugins but is newly reachable for apps, and is tracked separately.

Also outstanding from the same review and not addressed here: `stringify`/`coerce` asymmetry for non-scalar `@CXAppField` values, `getDeclaredFields`/`getDeclaredMethods` ignoring inherited members, the method cache keying on name so overloads collide, `@CXAppMethod` defaulting to no permission while `@CXAppField.writable` correctly defaults to false, and `coerceArgs` padding missing arguments with null instead of reporting an arity error.

No automated test exercises this path. `CXAppUnitTest` calls `CXAppServer.handle()` directly, so it never reaches `CXAppDispatcher` or the NodeMesh `APP_REQUEST` case. The behaviour that matters (a deliberately blocking handler must not prevent the node from processing other events, and a second request to the same app must queue behind the first rather than run concurrently) is currently unverified.

### CXApp error codes and failure disclosure

`CXAppResponse.error` carried ad-hoc prose (`"Forbidden"`, `"Unknown field: routes"`, `e.getMessage()`), which callers could not branch on and which leaked whatever a handler's exception happened to say.

`CXAppError` replaces those with a stable code plus a rewordable human message. `CXAppResponse` gains a `message` field; `error` continues to carry the machine code so existing consumers keep working, and `BROWSER_NOT_ALLOWED` retains its exact token since CX-PROTOCOL.md documents it and the browser extension branches on it. The raw-string `fail(String, String)` overload is retained for call sites not yet migrated.

**Failure disclosure**

Refusals previously distinguished unknown target, unreadable or unwritable field, and denied permission, and existence was answered before permission was checked. Any peer able to reach an app could therefore enumerate its fields and methods, and learn their readability, by comparing replies, without holding a single permission.

Those cases now collapse to one `FORBIDDEN` answer. The permission is stored on the field or method, so when the target does not exist there is nothing to check; returning a distinct code in that case is what made enumeration possible. The same reasoning merges the unknown-app case, so a peer cannot discover which apps a node has registered.

`CXAppServer.debugPermission()` re-opens the detail for callers that hold it: they receive `UNKNOWN_FIELD`, `UNKNOWN_METHOD`, `PERMISSION_DENIED`, `FIELD_NOT_READABLE` or `FIELD_NOT_WRITABLE` as appropriate. It grants detail only and never access; an operation refused without it is refused with it, only more informatively. All refusals are logged locally at debug level regardless, so the node operator can always diagnose one the caller was told nothing about. `CXAppServer.refuse()` is the single place that decides how much a refusal reveals.

The permission is per app, not global: it is the app's own ID plus `CXAppServer.DEBUG_PERMISSION_SUFFIX`, so an app with ID `RProx` uses `RProx.debug` and holding it says nothing about any other app. This is the framework composing its own permission name, the same way the network layer composes chain scope into names like `Record-3`.

`BasicPermissionContainer` stores a flat action string and has no scope concept, which its javadoc is explicit about ("designed to be embedded in many server applications", and `Actions` notes its constants are unlikely to suffice). Composing scope into the name is therefore the caller's job by design. For annotation permissions the caller is the app author, so `@CXAppField(permission="admin")` collides with any other app on the node using the bare word `admin`, and authors should namespace their own strings (`permission="RProx.admin"`) exactly as core does. No framework change is needed for this; it is a convention to follow, not a defect in the container.

A permitted caller that mistypes a field name now receives `FORBIDDEN` rather than a specific error unless it holds the app's debug permission, which is the intended trade and the reason the permission exists.

The NodeMesh `APP_REQUEST` path uses the same codes. A request naming an unregistered app now receives `FORBIDDEN` rather than no reply at all, so it is answered immediately instead of the caller waiting out `APP_RESPONSE_TIMEOUT_MS` (5000ms) for a response that was never coming, and the code is deliberately the same one an unpermitted or unknown target produces so app presence stays unenumerable. A saturated lane returns `BUSY`, and a handler that throws returns `HANDLER_ERROR` with the throwable logged locally rather than placed on the wire.

Handler exceptions no longer put `e.getMessage()` on the wire. Failures return `HANDLER_ERROR` and the message is logged locally, since handler text is arbitrary developer output and may name internal state.

### CXApp field value encoding

Field values crossing the wire in `CXAppResponse.fields` were written in one form and read in another, so every non-scalar field was silently wrong in both directions. All three conversion sites date from `d42da0f Begin CXApp system`; the scalar cases were written first and the cxJSON1 branch was added only to the one place it was immediately needed, which was POJO arguments for `INVOKE`. CX-PROTOCOL.md describes the map only as `{fieldName -> value}` and states no encoding, so neither side was written against a rule. There was no design intent to preserve here.

On the sending side, `CXAppServer.stringify` was `String.valueOf` for every type. A `Map` field went out as `{a=b}`, which is not cxJSON1 and cannot be parsed by any client, while `coerce` on the same class read that same field back through `ConnectX.deserialize("cxJSON1", ...)`. `READ`, the `WRITE` confirmation and `REFRESH` all returned that unparseable text with `success` set true.

On the receiving side, `CXAppClient.coerce` had no cxJSON1 branch at all. A non-scalar value fell off the end of the method and the field was applied as `null`, inside a path that logs nothing on that route. Correcting only the sender would have left collection and POJO fields still arriving as `null`.

`stringify` now writes scalars as plain text and everything else as cxJSON1, matching what `coerce` reads, and `CXAppClient.coerce` gains the same cxJSON1 fallback the server side already had. A new `CXAppServer.isScalar` is the single definition of which types travel as plain text, so the two halves cannot drift apart again; it lists both the primitive and boxed spelling of each scalar because `coerce` tests a declared type, which may be primitive, while `stringify` tests a runtime class, which never is.

`stringify` now declares `throws Exception` rather than falling back to `toString` on a value it cannot serialize. Emitting an unparseable string under `success` is the failure being fixed, so it is not reintroduced as a fallback; `handle()` converts the throw to `HANDLER_ERROR` and logs it against the app. The client keeps returning `null` on a parse failure, since `applyField` treats one bad field as non-fatal to the rest of a `REFRESH`, but every failure is now logged rather than reached by silent fall-through.

`CXAppClient.buildHTML` is unchanged and still renders with `String.valueOf`. It reads the client's own local fields for display to a person, not for transport, so plain text is correct there.

Known gap, unchanged by this: a field declared as `Object` is still ambiguous, because `stringify` dispatches on the runtime class while `coerce` dispatches on the declared type. An `Object` field holding a `String` goes out as plain text and comes back through the cxJSON1 branch. Declared-`Object` fields should be avoided until the wire format carries its own type tag. An empty string also still round-trips to `null`, since `stringify` writes `""` for null and `coerce` maps `""` back to null; this predates the change.

### CXApp in-process calling API

CXApps could not be called from Java. The only code path that sent an `APP_REQUEST` was `AppServlet.fireAndWait` inside `HTTPBridgeProvider`, reachable only over loopback HTTP from the browser extension, so an app was callable from a browser and from nowhere else. `CXAppClient` was a render-only half: its entire surface was `loadTemplate`, `getTemplate`, `buildCache`, `applyAndRender` and `buildHTML`, with a `targetCXID` field and no method that sent anything. Nothing on `ConnectX` filled the gap, and CXNexus contains no CXApp references at all, which is consistent with there having been no API to write against.

Two consequences beyond the missing entry point. Every outbound request was hardcoded `fromBrowser = true`, so an app declaring `browserEnabled() == false` was unreachable by everything, because the non-browser path it was reserving itself for did not exist. And the response side was equally browser-bound: the NodeMesh `APP_RESPONSE` handler delivered only to `HTTPBridgeProvider.deliverAppHTML`, so a Java-originated response would have been rendered and then dropped into a bridge map holding no matching sid. `CXAppRequest`'s javadoc has described `fromBrowser = false` as "called in-process from CXNexus" since the framework was introduced, so this was always the intent and simply was never built.

**Correlation registry**

`CXAppRequests` correlates an outbound request with the response answering it, keyed on the event sid. This is the same mechanism as the bridge's `pendingAppHTML` map, lifted out of the bridge and owned by `ConnectX` so both callers can reach it. The two registries stay separate rather than merged: both are sid-keyed, a browser request has no entry in the new one and an in-process request has no bridge queue, so each is a no-op for the other's traffic and the browser path is bit-for-bit unchanged.

**Public API**

`ConnectX.sendAppRequest(appID, targetCXID, op, target, args, ttlMs)` returns a `CompletableFuture<CXAppResponse>`, with an overload using the configured default. `CXAppClient` gains `read`, `write`, `invoke` and `refresh` built on it, using the `appID` and `targetCXID` it already holds. `ConnectX.registerApp(CXAppClient)` now attaches the node to the client, and a client that was never registered fails its future with a stated reason rather than a NullPointerException.

A response with `success == false` completes the future normally: the server answered and the answer was a refusal, described by `CXAppResponse.error`. Only transport-level problems, timeout and lane saturation, complete it exceptionally, so callers branch on `error` rather than catching.

**Threading**

Futures are completed on a `CXAppDispatcher` lane, never on the EventProcessor. Completing inline would run every caller's `thenApply` and callback chain on the single thread serving the whole mesh, which is precisely the stall the dispatcher was added to prevent, reintroduced through the front door of the public API. A timeout completes on the common ForkJoinPool delay scheduler, so the guarantee holds when no response ever arrives. `CXAppRequestsTest` asserts the callback does not run on the delivering thread rather than leaving it to review.

**Declared timeouts**

`@CXAppField` and `@CXAppMethod` gain `ttlMs`, defaulting to zero meaning use `NodeConfig.appRequestTimeoutMs`. A slow operation declares its own wait instead of the node-wide default being raised for every app.

These are read from the CXAppClient's own annotations, not the server's, because the timeout is enforced by the waiting caller and a caller cannot see the server's annotations. For fields this is already natural, since a client mirrors the fields it expects. For methods, a client declares one it intends to call by annotating a same-named stub; the stub is never executed, as INVOKE runs on the server, and exists only to carry the declared wait. `CXAppClient.buildCache` now scans methods for that purpose and reports the declared timeout count alongside the field count. Declaring `ttlMs` on the server side is harmless but has no effect, and both annotations say so.

`HTTPBridgeProvider`'s hardcoded `APP_RESPONSE_TIMEOUT_MS = 5000` is replaced by the same `NodeConfig` value, read per call, so the browser and in-process paths cannot disagree about how long a caller waits.

**Conversion consolidated**

`CXAppServer` and `CXAppClient` each held their own copy of the value conversion, and two copies of a symmetric rule is what let one half be changed without the other in the first place. Both now delegate to `CXAppCodec`, which holds the single definition of `isScalar`, `stringify` and `coerce`. Behaviour is unchanged; the duplication that caused the encoding defect is gone. `CXAppClient.write` and `invoke` encode their arguments through the same codec the server decodes with.

**Tests**

`CXAppRequestsTest` adds 7 tests covering the path that previously had no coverage: correlation on matching sid, completion off the delivering thread, timeout when unanswered, unknown and null sids ignored, pending entries released on both completion and timeout, failure responses completing normally, and concurrent requests answered out of order. Suite is 26 tests with `CXAppUnitTest`.

Not covered: nothing yet exercises `sendAppRequest` against a live peer, so the registry is proven in isolation while the wire path is not.

### Security: node temp-import verification

Peer nodes are no longer written to disk before signature verification. The old pattern added nodes to `PeerDirectory` and persisted `.cxi` files before the signing key was checked, then called `removeNode` on failure. `CryptProvider` now exposes `hasCert`, `cacheKeyFromString`, and `removeCert`. All three NodeMesh temp-import paths (CXHELLO/NewNode first contact, PeerFinding, relayed NewNode) now do a cert-cache-only provisional load -- no disk write, no PeerDirectory entry -- and only persist via `addNode` once all verifications pass. Rollback calls `removeCert` guarded by `certAlreadyPresent` so a key that was already cached before the import is never evicted.

### Bug fixes

**CXST stream mux header parsing** -- when SocketWatcher buffered exactly the 4 magic bytes, IOThread read `idLen` from the socket but never stored it in `header[4]`. `readNBytes` then requested one extra byte that never arrived and blocked for 1 second. Fixed by writing the socket-read `idLen` into `header[4]` and advancing `have` to 5 before `readNBytes`. CXST detection also moved fully into IOThread.

**RetryProcessor CXN fallback for discovery events** -- `NewNode`, `CXHELLO`, and `CXHELLO_RESPONSE` were being converted to E2E-encrypted CXN broadcasts on retry. These carry the public key so encrypting them is circular, and `stripSignature` on the receiver can't process an encrypted blob. Discovery events now fall back to a signed-only CXN broadcast. The already-signed `ne.d` is forwarded as-is -- the old code re-applied `.signData()` which double-signed the payload and caused JSON parse failures on the receiver.

**Non-clean startup NPE** -- after a PGPainless update, `secretKeyRing()` returns `null` instead of throwing when handed an encrypted key file. The existing try/catch only caught exceptions so `secretKey` stayed null and `new OpenPGPKey(null)` NPE'd. Fixed with an explicit null check that falls through to the passphrase-decryption path in both cases.

### LAN scanner and peer discovery backoff

LAN scanner changed from a fixed 5-minute cycle to run-once on startup then every 15 minutes. Hook point left for Global Scanner (not yet implemented).

Persistence thread peer-discovery replaced with configurable time-based backoff: 30s, 60s, then 10-minute steady-state. Values in `NodeConfig` (`peerDiscoveryBackoff1Ms`, `peerDiscoveryBackoff2Ms`, `peerDiscoverySteadyMs`). Removes the `cycleCount >= 1` test hack.

### Integration tests

`MultiPeerTest` rewritten as JUnit 5 integration tests: E2E encryption, permission enforcement, spoofed-sender rejection at 003, and signed/unsigned message delivery/rejection at 004.

---

Note: the `CXST` mux header and several routing changes in this release are part of a larger ongoing repackage.

### Stream sessions

Full bidirectional stream sessions between peers are now operational (`CXStreamPlugin`). Open a session with `openStream(targetCxID, localHost)`, accept with `acceptStream(session)`, write chunks with `session.write(data)`, and close with `session.close(cx)`. Sessions use direct TCP by default (main-port mux via `CXST` header on the existing P2P port) and upgrade to WebSocket when both sides have a working HTTP bridge.

Bridge transport is negotiated by the receiver. Before advertising a WebSocket address in ACCEPT, the receiver probes its own health endpoint and verifies the response identity matches its own node ID. If the external URL routes to a different server (common in test environments with placeholder bridge addresses), it falls back to TCP. `NodeConfig.streamBridgeOnly = true` disables TCP entirely for nodes behind reverse proxies where exposing a direct IP would defeat the point.

### Retry and routing fixes

**CXS to CXN fallback** now excludes only low-level discovery events (`CXHELLO`, `CXHELLO_RESPONSE`, `PeerFinding`), which cannot be converted because they target unknown peers and E2E CXN broadcast requires a known target cert. All other CXS events (MESSAGE, STREAM, NewNode, etc.) fall back to CXN broadcast with E2E encryption after the retry threshold. **BridgeHealthMonitor** removed from routing. It was marking entire bridge protocols as degraded based on per-peer failures, blocking all bridge-addressed peers when the seed node was unreachable.

### Bootstrap and verification fixes

**NewNode relayed verification** now uses `ib.ne.d` (original signed bytes) instead of already-stripped `eventData`. For nodes not yet in peerDirectory, a memory-only entry is added before cert lookup and rolled back on failure. **`cacheCert` NPE** (`log.info(n.toString())` before null check) fixed. It was silently returning false for every EPOCH event until bootstrap completed. **EPOCH key pre-cached** at `initializeCrypto()` time so seed node events can be verified immediately, before the async bootstrap file load finishes.

### Security hardening: seed and peer ingestion

**Seed peer blobs** (`Seed.hvPeers`/`peerFindingNodes` as raw `Node` objects) replaced with signed blobs (`hvPeerBlobs`/`peerFindingNodeBlobs` as `List<byte[]>`). Each blob is a node signed by its own key, the same format used in CXHELLO. Seeds built via `signAndPublishNetworkSeed` and `initEpochBootstrap` now call `signSelfNode()` to produce the blob. `Seed.fromCurrentPeers` pulls from `PeerDirectory.signedNodeCache` so only nodes with verified signed entries are relayed.

On ingestion (`applySeed`, `applySeedConsensus`) each blob is verified: strip signature, deserialize node, cache key via `cacheKeyFromString` (never replaces existing), verify signature, then `addNode(node, blob, cxRoot)`. Blobs that fail verification are dropped.

**`cacheKeyFromString`** added to `PainlessCryptProvider`. Parses a base64 PGP key and caches it with `putIfAbsent`. `cacheEpochKeyFromFile` also fixed to use `putIfAbsent` (was `put`, could silently overwrite a trusted key).

**PeerDirectory node replacement policy:** `PeerDirectory.addNode` allows replacing an existing entry when the incoming node's public key matches the stored key. A node can re-announce itself with updated address or port data and that update is valid because it is signed by the same identity. Replacing a node with a different public key throws `SecurityException`. Key and cert cache entries in `CryptProvider` are always `putIfAbsent`. Node entries in `PeerDirectory` are mutable by their own signer.

**`NetworkDictionary.dynamicSeed`** flag added. `false` (default): seed must be NMI/backendSet signed. `true`: any known peer can sign and distribute the seed. The flag is embedded in the signed seed so relayers cannot forge it.

### Plugin system: sender identity at all data levels

`CXPlugin` now has `handleEvent(Object data, String senderCxID)` alongside the existing `handleEvent(Object data)`. The default implementation delegates to the single-arg overload so existing plugins are unaffected. `sendPluginEvent` resolves the origin sender from `ne.p.oCXID` (survives relay) with fallback to `nc.iD`, and calls the sender-aware overload at all three data levels (`NETWORK_EVENT`, `INPUT_BUNDLE`, `OBJECT`).

### `CXMessagePlugin` and `CXMessage`

`CXMessage` is the typed payload for `MESSAGE` events (`text` + `timestamp`, serialized as cxJSON1). `CXMessagePlugin` switched from `DataLevel.NETWORK_EVENT` to `DataLevel.OBJECT` with `type = CXMessage.class`. The `onMessage(String senderID, CXMessage message)` callback receives both the typed object and the verified origin sender cxID.

This also fixes a silent delivery failure. NodeMesh always calls `verifyAndStrip(ne.d)` and events sent without `.signData()` or `.encrypt()` were being rejected before reaching any plugin. The `CXMessage` + `.signData()` path goes through proper signature verification and sets `verifiedObjectBytes` for `readyObject()`.

### Network join API

`ConnectX.joinNetworkFromPeers(String networkID)` sends `SEED_REQUEST` to EPOCH first (authoritative), then to all other HV peers. Used for joining non-CXNET networks without NMI-level bootstrap.

`Seed.fetchOfficial(ConnectX)` tries `joinNetworkFromPeers("CXNET")` first, falls back to `https://anvildevelopment.us/downloads/cxnet-bootstrap.cxn` via OkHttp.

### Bootstrap stability

`AtomicBoolean bootstrapStarted` guards `attemptCXNETBootstrap`. Prevents concurrent duplicate bootstrap calls that previously caused BouncyCastle `LongDigest` (SHA-512) thread-safety crashes. Reset on failure so retries work.

`PeerDirectory.addNode` changed from throwing `IllegalStateException` on invalid nodes to logging a warning and returning. Prevents bootstrap failures from propagating as uncaught exceptions.
