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
