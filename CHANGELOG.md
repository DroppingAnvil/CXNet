# Changelog

## v0.4 - v0.4.2

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

## v0.6.0

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

### Independently signed network blobs

Seed network distribution moved from the informational `networks` field to independently signed per-network blobs in `Seed.networkBlobs`. Each blob is verified against that network's own trust keys rather than relying on the outer seed signature alone, so a seed can carry a network without the seed's signer becoming that network's authority.

`ConnectX.verifyNetworkBlob` handles both forms: a single-layer blob (`signedByTrusted{CXNetwork}`) and a two-layer cosigned blob (`signedByEPOCH{signedByNMI{CXNetwork}}`).

`importTrustedNetworkSeed`, the NETEPOCH handler, cosigns each incoming network blob with EPOCH's key, stores it as `cosigned.cxnb` for redistribution, then calls `applySeed`. `storeCosignedNetworkBlob` and `readExistingNetworkBlob` prefer `cosigned.cxnb` over `seed.cxn` where both exist.

`requestPeerLookupAsync` gained an `epochOnly = false` path so cosigned blobs can be sourced from any high-value peer rather than only EPOCH. The `IOJob` `CALLBACK` type was wired for async completion dispatch to support it.

`Seed.networkBlobs` carries javadoc distinguishing it from the informational `networks` field, alongside an `addNetworkBlob` helper.

### Cosigned peer identities

`PeerDirectory` gained cosigned identity storage as `.cxic` files, mirroring the existing `.cxi` pattern, plus `PendingPeerLookup` retry and registration state. `PeerDirectory.lookup()` falls back to a `.cxic` cosigned identity when no `.cxi` exists, using local disk and crypto verification only so it remains safe to call from any thread.

Cosign production was added at the three existing import sites (CXHELLO, NewNode, PeerFinding), gated to EPOCH and the CXNET `backendSet`. `verifyAndImportCosignedIdentity` verifies the `{signedByTrusted{signedByPeer{Node}}}` chain. `PEER_LOOKUP_REQUEST` and `PEER_LOOKUP_RESPONSE` handlers were wired.

`performSeedConsensus` resolves a claimed NMI through peer lookup, across EPOCH and all known high-value peers, and retries verification once resolved. Trust rests in the signature rather than in the responder. While AnvilDevelopment manages the global network through EPOCH, core trust remains each peer's own cryptographic contract: a peer's node data stays signed by that peer and is not modifiable by anyone else, consistent with the rest of CXNET where a signed entry from a source can only be superseded by new data signed by that same origin.

`ConnectX.requestPeerLookupAsync` and `sweepPendingPeerLookups` provide non-blocking registration with a 15 second, 5 attempt retry sweep on the persistence thread.

### CXNET seed distribution completeness

`signAndPublishNetworkSeed("CXNET")` now refreshes each non-CXNET network from its persisted `networks/<id>/seed.cxn` before bundling, so the distributed seed always reflects the latest on-disk configuration. It also updates `signedBootstrapSeed` in memory, so `SEED_REQUEST` responses are current without a restart.

After `restoreJoinedNetworks`, authoritative nodes republish the CXNET seed to fold restored networks such as CXChat into the distribution seed at startup. The async bootstrap `doAfter` path does the same for nodes where CXNET loads after the IOJob.

EPOCH's `SEED_REQUEST` handler includes every `networkMap` network in `dynamicSeed.networks` when answering a CXNET request, keeping it consistent with `epochSeedBlob`.

### File corruption and power-loss hardening

Reading a core file that fails now generates a `recovery.txt` within scope and locks the system out of writing that file through normal paths. `recovery.txt` must be deleted at runtime before the files are regenerated, so a corrupted or truncated file is never silently overwritten with fresh state.

## v0.6.1

### Trust chain: NETEPOCH scope and cosigned blob authority

NETEPOCH held far more authority than intended and could in principle rewrite itself as an independent CX network without authorization. Verification and the inner and outer signature processing are now split properly so the subject network's backend set is verified in depth. This breaks the originally specced trust model, but the exposure existed only from NETEPOCH and from data signed by the NETEPOCH or CXNET NMI; no other path was affected.

Cosigned blobs also held too much authority. They were trusted to supply the CXID and network set even when the network already existed on disk, violating the rule that data on disk is always trusted and that historical data outranks new network input. Resolved.

`ConnectX.verifyNetworkBlob` used the result of `deserialize` in a later null check for cosigned data. A binary deserialize would error and fall silently into the catch block, returning null, instead of running the cosigned logic.

Keys for the inner and outer signatures were grouped into one set. The two layers exist to provide plane separation, and grouping them introduced a potential trust issue. They are now separate.

Major: CXNET EPOCH could sign a seed for any network and `verifyNetworkBlob` would accept it.

`MultiPeerTest` was adjusted to be realistic for a fresh network start. It is not a performance test, and giving individual tests ten seconds while a network is bootstrapping from cold was not practical. A dedicated performance test is still to come.

## v0.6.2

### Seed join consensus

A network could not be joined unless three distinct peers answered the seed request. Only responses arriving directly from EPOCH were applied on sight; every other response, including ones carrying a valid EPOCH-signed blob, was parked in the consensus map behind a hard threshold of three responders. On a network with fewer than three reachable peers holding the subject network the map sat at 2/3 indefinitely, with no timeout, and the join never completed. Reproducible on CXChat, where only EPOCH and the CXChat node itself could answer.

Under the cosignment system the relaying peer's identity carries no weight: the outer seed signature verifies against EPOCH and the CXNET backend set, and each bundled network blob verifies independently through its own cosignature chain. A relayed blob proves itself, so it is applied as soon as `applyBackendSignedSeed` accepts it regardless of who forwarded it. Consensus is unchanged and still handles every response that fails verification or arrives without a blob, including the claimed-NMI peer lookup recovery path. Nothing is trusted that was not trusted before; the vote is skipped only where the signature chain already gives a stronger guarantee than a peer count.

### Cosigned seed distribution for non-CXNET networks

`applyBackendSignedSeed` reported success as soon as the outer signature verified and the seed deserialized, without confirming the requested network was present in what it applied. A bootstrap seed signed before a network was registered verifies perfectly and contains nothing relevant, so a join could be reported complete while the network was never registered. Combined with the relayed-seed path this silently ended the join: the response was consumed, the consensus map cleared, and no warning written anywhere, which is why CXChat joins appeared to do nothing in either log. Success now additionally requires the target network to be present in `networkMap` afterwards.

Seeding a network other than CXNET had no correct source. The `SEED_REQUEST` handler answered every request with `signedBootstrapSeed`, which is always the CXNET bootstrap regardless of the network asked for, and worked only because that bootstrap happens to bundle the cosigned blob of every registered network. A responder whose bootstrap copy predates the requested network answers with a blob that does not contain it. The dynamic seed cannot cover the gap either: `Seed.fromCurrentPeers` populates only `hvPeerBlobs`, and `applySeed` imports exclusively from `networkBlobs`, so a dynamic seed can never import a network at all.

`SeedExchange` now carries `cosignedNetworkBlob`. A responder attaches the stored `networks/<id>/cosigned.cxnb` when it holds one, and the receiver seeds from that in preference to any seed blob. This is the form the cosignment system exists to distribute: the outer cosignature over the network NMI's inner signature makes the blob self-proving, so the identity of the supplying peer is irrelevant and any peer holding a stored copy can serve it, the network's own NMI included but not required. A new field was required rather than reusing `epochSeedBlob`, which is stripped and deserialized as a `Seed` and would fail on a network blob.

`readCosignedNetworkBlob` reads only `cosigned.cxnb`, deliberately not `readExistingNetworkBlob`, whose fallback returns a single-layer blob signed by the network's own NMI that a requester who has never held the network cannot verify. `applyCosignedNetworkBlob` verifies both signature layers through `verifyNetworkBlob`, rejects a blob whose netID is not the network requested, and only then registers it. The dynamic seed and chain heights are still sent and are now informational only.

### Self-verification and seed request correctness

A node could not verify a signature it had produced itself. `cacheCert` resolves an unknown cxID through the peer directory, which never contains the node's own entry, and nothing placed the node's own public key into the cert cache at startup, so every `verifyAndStrip` against our own cxID failed with "cacheCert returned false". This broke a network master identity serving its own network: the inner layer of a cosigned blob is signed by the network's NMI, so on the NMI itself that inner signature is its own. `restoreJoinedNetworks` rejected its own `cosigned.cxnb` and fell through to a `seed.cxn` that no longer existed, `verifyAndStoreCosignedBlob` failed on every inbound copy and never wrote `cosigned.cxnb`, and `readCosignedNetworkBlob` consequently had nothing to serve, so the NMI answered every seed request for its own network with `cosigned=none`. Confirmed on CXChat, where the blob was valid and correctly structured and verified without issue on every other node.

The node's own public key is now cached under its own cxID immediately after crypto setup. This lives in `ConnectX` rather than in the provider and uses only `CryptProvider.cacheKeyFromString` and `CryptProvider.getPublicKey`, so it applies to any provider implementation and the crypto layer stays pluggable. `cacheKeyFromString` only ever does `putIfAbsent`, so a key learned from the network is never overwritten, and only the node's own cxID is affected.

`SEED_REQUEST` and `SEED_RESPONSE` both silently defaulted an unreadable network to CXNET. On the request side `requestedNetwork` was initialised to `"CXNET"`, so a request whose payload failed to deserialize, or carried no network field, was answered with a CXNET seed; the requester consumed that response, applied a seed that could never contain the network it asked for, and waited indefinitely with nothing logged. The response side defaulted the same way, which would apply a mislabelled response to CXNET and strand whatever join was actually pending. Neither side defaults now. A request that cannot be read is malformed rather than a CXNET request, and is logged and ignored. A response falls back only to its own dynamic seed's networkID and is otherwise logged and dropped.

Seed requests and responses now name the network on both sides, and the responder reports the cosigned blob size, so a peer holding none is visible as `cosigned=none` rather than being indistinguishable from one that attached a blob. This is what identified the failure above.

### Event delivery limits

`EventType` had declared `ttlMs`, `maxPayloadBytes` and `maxRetries` per event for some time, but nothing read them. Every queued event used a flat `MAX_RETRIES` of 50 with exponential backoff to a five minute ceiling, so a request could be retransmitted for hours after it stopped being relevant. EPOCH was observed re-broadcasting `SEED_REQUEST` events for CXChat long after the network had been imported through NETEPOCH, because the retry queue tracks delivery failure and has no visibility into the intent being satisfied elsewhere.

`RetryBundle` resolves `maxRetries` and `ttlMs` once in its constructor. A recognized event type contributes its declared limits; anything unresolvable takes the existing default and no expiry, exactly as before. Both are final primitives, so no caller re-resolves a type or handles a null, and adding an event type never adds state or branches to `RetryBundle`.

`shouldRetry` and `hasExceededMaxRetries` are replaced by a single `disposition()` returning `READY`, `WAITING`, `EXPIRED_RETRIES` or `EXPIRED_TTL`, which `RetryProcessor` switches on. TTL expiry is newly enforced, giving stale events a bounded lifetime instead of relying on the retry ceiling alone. Retry logs report the bundle's real cap rather than the flat constant. `SEED_REQUEST` now honors its declared 60 second TTL and 10 attempts instead of 50, so an unanswered seed request is dropped in about a minute and the join logic reissues a fresh one rather than the mesh carrying a stale broadcast.

`ttlMs` is enforced in the retry queue only. Extending it to the main output queue, and enforcing `maxPayloadBytes`, remain outstanding.

Explicit INFO loggers were added for the test runners in `logback-test.xml`. Root is WARN, so `BootstrapServerTest` and `MultiPeerTest` output was being suppressed entirely during bootstrap debugging.

## v0.6.3

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

### CXApp inherited members and strict argument arity

**Inherited members**

`buildCache` scanned `getClass().getDeclaredFields()` and `getDeclaredMethods()`, which see only members declared on the concrete class. Member annotations are never inherited in Java, so a shared base app declaring `@CXAppField` or `@CXAppMethod` members had all of them silently absent from the cache, and any class hierarchy was impossible. Since the FORBIDDEN merge this surfaced as a permission error rather than a missing field, so the symptom pointed at `cxidAppPermissions` and the grant logic instead of at reflection scope.

Both `CXAppServer.buildCache` and `CXAppClient.buildCache` now walk the class chain, stopping before the framework base class. The walk runs most-derived first and skips a name already cached, so an override or a shadowing field wins over the base declaration. A subclass that overrides an annotated method without re-annotating it keeps the base annotation, and the cached base `Method` still dispatches virtually to the override, so the subclass body runs under the base permission.

On the client, method presence is tracked in a separate set rather than by the timeout map, because a subclass declaring `ttlMs = 0` means "use the default" and must still shadow a base class that declared a value; keying on the map alone would let the base value leak through.

Overloaded `@CXAppMethod` names still collide, since the caches are keyed by name alone, and which overload wins remains undefined. That is unchanged and now stated in CX-PROTOCOL.md.

**Argument arity**

`coerceArgs` padded a short `INVOKE` call with nulls and ignored extra arguments. A null into a primitive parameter threw out of `Method.invoke` and was reported as `HANDLER_ERROR`, and a null into a reference parameter was handed to the method quietly. Neither told the caller what was actually wrong.

`handleInvoke` now requires the argument count to match the parameter count exactly and answers `MISSING_ARGUMENT` naming both counts. The caller is already past the permission check at that point, so the detail is safe to state plainly. `coerceArgs` no longer pads or truncates and documents that arity is the caller's responsibility, enforced above it.

Varargs `@CXAppMethod`s do not work and now fail cleanly instead of confusingly: `getParameterTypes()` reports `String...` as one `String[]` parameter, so a varargs method appears to take exactly one argument. Previously a spread call was silently truncated to its first argument and then failed trying to read that string as an array; it is now rejected as an arity mismatch. Documented in CX-PROTOCOL.md with the recommendation to declare an explicit parameter list or take a single cxJSON1-encoded collection.

**Tests**

`CXAppHierarchyTest` adds 12 tests: inherited field readable, inherited method invokable, subclass's own members unaffected, shadowing field resolving most-derived, unannotated override dispatching virtually under the base permission, `REFRESH` including inherited fields, inherited writable field written, a genuinely absent field still refused as `FORBIDDEN`, and arity too few, too many, exact, and a no-arg method with a null args array. Suite is 38 tests across the three CXApp test classes.

**Documentation**

The CX-PROTOCOL.md CXApps section was behind the last several changes and described a framework reachable only from a browser. It now covers the in-process calling API and its failure semantics, handler threading and lane ordering, value encoding with the `Object` and empty-string caveats, argument arity and varargs, permission namespacing, the default-open `@CXAppMethod`, failure disclosure and `<appID>.debug`, declared timeouts and where they are read from, and inherited members. `Last Updated` refreshed.

### Security review fixes

Findings from a multi-agent review of the CXApp surface together with the infrastructure defining its trust boundary: the serialization provider, the NodeMesh handlers establishing sender identity, signature verification, the permission store, and the loopback HTTP bridge. Each was verified against the source before being acted on; two did not survive that check and are recorded below as corrections rather than fixes.

**Peer blob signatures were verified and the result thrown away**

`NodeMesh.applySeedConsensus` called `verifyAndStrip` on each seed peer blob and discarded the boolean, then ran `addNode` unconditionally. Every other `verifyAndStrip` call site in the class gates on the result; this one did not, so a blob whose signature did not verify was stored anyway.

The consequence is worst for an identity already in the cert cache. `cacheKeyFromString` is `putIfAbsent`, so a blob claiming a known cxID is checked against that identity's real cached key rather than the key travelling in the blob, fails, and was still written, overwriting a verified peer record with an attacker-supplied one. For an unknown cxID the blob is self-signed and verifies on its own key, which is the same provisional first-contact trust every other import path extends, so nothing changes there.

The result is now captured and a failed blob is skipped with a warning naming the cxID. No other part of the loop changed. The surrounding `catch` still logs "verification failed", which only ever fired for a thrown exception and never for a false return; the new warning distinguishes the two.

**APP_RESPONSE was applied without checking it answered a request we sent**

The `APP_RESPONSE` handler resolved the client from `appResp.appID`, a field of the payload, and called `applyAndRender`, which writes the response's values into that client's fields. Nothing checked that the response answered a request this node had issued, or that it came from the peer the request was addressed to. Any peer able to reach the node could send an unsolicited `APP_RESPONSE` naming a registered app and inject state into it. Satisfying a genuinely pending request additionally requires guessing a UUID sid, but the field injection did not.

`CXAppRequests.Pending` now records the `targetCXID` the request was sent to, and `CXAppRequests.expects(sid, appID, peerCXID)` requires all three to match. `HTTPBridgeProvider` gains `pendingAppTargets`, maintained in exactly the same two places as `pendingAppHTML`, so the browser path is covered by the same rule, and `ConnectX.isExpectedAppResponse` consults both. A response that does not match is dropped with a warning before `applyAndRender`: nothing is applied, completed or rendered.

The comparison is against the response's origin, `ne.p.oCXID` with an `nc.iD` fallback, which is the identical derivation the `APP_REQUEST` case already uses. The finding described this as the responding peer's CXID, which would have been `nc.iD` alone and would have rejected every legitimately relayed response.

**IOThread ran doAfter twice with contradictory outcomes**

`processJob` catches its own exceptions, calls `doAfter(false)`, sets `success = false` and returns `false`, so a failure almost never reaches the `catch` in `run()`. `run()` discarded that result, forced `success = true` and called `doAfter(true)`, so a failed job ran its completion hook twice, the second time claiming success. `run()` now only completes the job when `processJob` returns true. On success the root job's `doAfter` remains this loop's responsibility, which is why `processJob` skips it when `root` is true.

This is the second time this defect has been addressed. Commit `43809a6` in the 0.6.0 line reported it fixed, "by restructuring so success path is inside try block", and that did remove the duplicate call from the catch path. It did not address the discarded return value, so the same double invocation remained reachable through the ordinary failure path where `processJob` handles its own exception and reports failure by return rather than by throwing.

**assert used for a runtime null check**

`NodeMesh` guarded `originPub` with `assert originPub != null` before dereferencing it. Assertions are disabled unless the JVM is started with `-ea`, so a null threw an NPE out of the following line and destroyed the diagnostic the branch existed to produce. A missing cached key is a likely reason verification failed in the first place, so it is now reported instead of treated as impossible.

**Field values could break out of an attribute**

`CXAppClient.escapeHtml` escaped only `&`, `<` and `>`. That is sufficient in text content, which is where it was reviewed, but `buildHTML` substitutes `{{field}}` wherever the developer's template places it, and the template chooses the context. A remote value landing in `title="{{name}}"` could close the quote and add an event handler attribute. It now escapes `"` and `'` as well, which is correct in both contexts.

Correction to the finding as filed: it stated that `escapeHtml` and `escapeAttr` both leave quotes unescaped. `escapeAttr` does escape quotes, and it is only ever applied to local developer values, never to remote data. The defect was context-blindness in `escapeHtml`, not a missing replacement in both.

**Bridge request bodies were unbounded**

`AppServlet.doPost` read the whole request body with `readAllBytes()` and no limit, while `CXServlet.doPost` in the same file caps at `NodeConfig.IO_MAX_INPUT` and answers 413. The cap and idiom are now copied verbatim into the app path. Note this bounds the loopback HTTP surface only; `EventType.maxPayloadBytes` is still not enforced anywhere on the mesh, which remains outstanding.

**Browser sessions were never evicted**

`appSessions` was populated on every `AppServlet` GET and removed nowhere, unlike `pendingAppHTML` which is cleaned in a `finally`. `AppSession` now carries a creation timestamp and expired entries are swept on the next GET, bounded by `NodeConfig.appSessionTtlMs` (one hour). Swept lazily rather than on a timer, since a session only matters to a request presenting it and there is nothing to reclaim between requests. CX-PROTOCOL.md previously documented sessions as persisting for the process lifetime; that is no longer the case.

**Peer hints no longer touch the peer directory**

`AppServlet` passed an `addr` query parameter to `registerPeerHint`, which built a `Node` carrying only `cxID` and `addr` and called `addNode`.

Correction to the finding as filed: this did not write an unverified node into the directory. `Node.validate` requires a non-null `publicKey` and `addNode` is `if (Node.validate(n)) { ... }` with no else, so nothing was ever stored. The real defect was a no-op that logged "Registered peer hint" as though it had succeeded.

The call is removed and the hint is deliberately left unapplied, with a debug line saying so. It was not made to work: a browser request is not an authenticated source of peer identity, every other import path verifies a signature before a node reaches the directory, and honouring a hint for an already-known peer would let a loopback request repoint that peer's address, converting a harmless no-op into a real vulnerability.

**Polymorphic deserialization allowlist narrowed**

`JacksonProvider` enables `activateDefaultTyping(ptv, NON_FINAL)`, so for a non-final declared type the JSON carries the concrete type id and a remote peer chooses what gets instantiated, bounded by assignability to the declared type and by the validator's prefixes. The validator listed `us.anvildevelopment.cxnet.`, `us.anvildevelopment.util.tools.permissions.` and then a bare `us.anvildevelopment.`, which subsumed both and widened the reachable set to the whole organisation namespace, including all of the Util dependency, a binary artifact outside this source tree and therefore unauditable from here.

The bare prefix is removed. Every `us.anvildevelopment.util` class this project references lives under `tools.permissions`, which remains allowed, so nothing in current use is affected. `java.util.` and `java.lang.` are unchanged.

No exploitable gadget was identified in `us.anvildevelopment.cxnet`: the tree has nine setters, none on a class plausibly reachable as a subtype of a CXApp field, the wire data classes use public fields so Jackson writes them directly without running code, and no constructor or setter reaches `exec`, JNDI, `Class.forName` or a classloader. This is a reduction in surface, not the repair of a demonstrated exploit.

This is the one change here that can fail at runtime rather than at compile time. A type reached only through reflection or a plugin would now be refused; `JacksonProvider` already carries a standing TODO to register plugin subtypes. Needs a bootstrap and multipeer run to confirm.

**Tests**

`CXAppRequestsTest` gains 5 tests for response attribution: a matching response accepted, a response from a peer that was not asked rejected, a response naming a different app rejected, an unissued or null sid rejected, and a satisfied sid refusing a second response. Suite is 43 tests across the three CXApp classes.

Not covered: the NodeMesh, IOThread, bridge and escaping fixes have no regression tests, and the narrowed allowlist is unverified at runtime.

### Administrative event authorization

Six c1 administrative event handlers applied their change with no authorization check. Signature verification upstream establishes who sent an event but not what they are permitted to do, and nothing between the two supplied that. This was not among the security review's findings; it was found separately while tracing the permission store.

**The gap**

`GRANT_PERMISSION`, `REVOKE_PERMISSION`, `BLOCK_NODE`, `UNBLOCK_NODE` and `REGISTER_NODE` each deserialized their payload and applied it immediately, using a `network` field supplied by the sender, with no check that the sender was the network's NMI, in its `backendSet`, or held any permission. The only gate resembling one is the scope check in `processNetworkInput`, which authorizes solely when `ne.p.scope` is literally `CXNET` or `CX` and solely against CXNET's own `backendSet`, while `EventBuilder` sets scope to `CXN`, `CXS` or `CXB`. It therefore never applied to these events, and would have consulted the wrong network's authority if it had.

The only prerequisite for reaching any of them is a valid CX identity, which any node joining an open network holds.

**Why GRANT_PERMISSION was the sharpest case**

`checkChainPermission` composes its lookup key as `permission + "-" + chainID` and resolves it against `networkPermissions`. The `GRANT_PERMISSION` handler wrote `grantData.permission + "-" + grantData.chain` into that same map. Identical structure, identical key construction, so the handler issued exactly the key the gate checks for.

`ConnectX.Event()` does gate chain recording, on `checkChainPermission(senderID, Record, chainID)`, and that gate is real. It is also what made the ungated handler an escalation rather than a nuisance: a sender could grant themselves `Record-<c1>` through the unauthorized handler and then satisfy the durable gate with their next event, which is written into c1 and persisted. Chain replay re-applies it from disk on restart, and because the event propagates, every receiving node performed the same ungated write. This was initially recorded in this changelog as transient local state; that was wrong.

`BLOCK_NODE`, `UNBLOCK_NODE` and `REGISTER_NODE` needed no such bootstrap. They call `saveDataContainer()` directly, so they were durable on disk immediately. `BLOCK_NODE` with `network` set to `CXNET` reaches `blockNodeCXNET`, so any peer could block arbitrary nodes network-wide.

**The rule**

`CXNetwork.checkAdministrativeAuthority(deviceID, permission)` grants authority to the network's own NMI, to a member of its own `backendSet`, or to a holder of the action's permission on that network. It lives on `CXNetwork` rather than in NodeMesh so the zero trust semantics sit beside the existing permission checks that share them.

Authority is the target network's and only the target network's, resolved from the payload's `network` field. CXNET's `backendSet` confers nothing on another network, which is the distinction the old scope check blurred.

Zero trust is honoured as the other checks in the class honour it. Once `zT` is active the NMI loses the NMI branch and also the `backendSet` membership it holds by being the first backend, falling through to `checkNetworkPermission`, which blocks the NMI under `zT` as well. A zT NMI is therefore fully demoted and must hold the permission like any other node. Other backends are unaffected, matching the existing scope of `zT`.

`Permission` already declared `BlockNode`, `UnblockNode` and `RegisterNode`, with javadoc reading "Typically NMI-only or designated moderators", and no handler had ever checked them, so these guards were designed and never wired. `GrantPermission` and `RevokePermission` are added to complete the set.

**Authorizing on origin rather than transmitter**

All six handlers authorize on `ne.p.oCXID` with no fallback to `nc.iD`. A null origin is refused.

`nc.iD` is whoever handed over the bytes. Authorizing on it does not merely require direct transmission, it accepts a relaying peer's identity in place of the author's, so an attacker-authored event relayed by any authoritative peer authorizes on the relayer. The payload keeps the attacker's `oCXID` and signature throughout.

`ZERO_TRUST_ACTIVATION` already checked the target network's `backendSet.get(0)`, which is the correct authority and the template the new guard follows, but it compared against `nc.iD`. An activation authored by anyone and relayed by that network's first backend passed. Zero trust is irreversible, making it the worst case for trusting a forwarder. It now compares against the origin. Its NMI-only rule is deliberately kept rather than widened to `checkAdministrativeAuthority`, since activation is the NMI's own act and broadening an irreversible operation would be a semantic change rather than a fix.

Requiring `oCXID` is safe because `EventBuilder` sets it at build time on every event (`ConnectX:950`, `ConnectX:977`), the payload signature is verified against that same field (`NodeMesh:906`, commented "Use oCXID (origin sender) for signature verification, NOT cxID"), and `oCXID` is cross-checked against `nc.oD` at `NodeMesh:890`, `923` and `1134`. The field is authenticated rather than claimed. Relay sites copy `scope` without touching `oCXID`.

**Shape of the change**

Five identical guards plus one static helper in NodeMesh, and one comparison changed in `ZERO_TRUST_ACTIVATION`. Each guard sits after deserialization and before the first mutation and uses the `handledLocally = true; break;` pattern `ZERO_TRUST_ACTIVATION` already used. No existing handler logic was restructured.

**Not covered**

No test exercises these handlers. `oCXID` is now load-bearing for six of them, so an admin path that somehow built an event without it would be refused where it previously succeeded, surfacing as a `Rejected:` warning rather than anything louder. Needs a live run with an NMI issuing a grant, a relayed grant, and an unauthorized grant to confirm the rule admits and refuses the right events.

## v0.6.4

CXNet routing, peer selection, and broadcast permissions.

Transport behaviour is unchanged from v0.6.3: broadcast permission enforcement ships disabled, so
the new permission layer logs its decisions without dropping anything. Existing deployments need no
migration for this release, though old bootstraps will before enforcement can be enabled -- see
"Not covered" at the end of this section.

### Peer directory: one scored, bounded store

`PeerDirectory` held four overlapping `Map<String,Node>` fields -- `seen`, `hv`, `lan`, `peerCache` -- and `addNode` wrote to two of them. None was capped or ranked, and socket addresses lived in a different object entirely (`DataContainer.localPeerAddresses`), so `getAllAddresses` joined two stores on every send.

Peer selection was therefore arbitrary. The CXN fanout built its target list from `hv` plus every entry in `seen`, in hash order, and tried every address of every peer. A node that had accumulated a few hundred dead entries spent its whole send budget on them, which is much of why meshing across networks was unstable.

**`PeerEntry` and the collapsed store**

One `PeerEntry` per cxID now owns the identity, the addresses, retry state and endpoint health together. `PeerDirectory.peers` is the single store; `seen`, `lan` and `peerCache` remain as `@Deprecated` projections over it so existing call sites keep working while they migrate. `seen` projects everything, `lan` filters on `localSubnet`, `peerCache` on `diskCached`, and a write through `lan` still means "on a local subnet". The projections return snapshots from `values()`/`keySet()`/`entrySet()`, because callers iterate them while sending and several mutate the directory as they go -- `ConnectX.purgeFromPeerDirectory` was changed to purge the backing store directly for that reason, since `removeIf` over a snapshot would have silently done nothing.

The `File peers` field was renamed `peersDir`, the store having taken the name.

**Priscore**

`3/(retries+1)` plus a tiered endpoint term, computed on read. Float division deliberately: integer division collapses 1.5 to 1 and flattens the top of the curve. The availability term never reaches zero, so a long-dead peer still outranks nothing at all, and a successful contact resets `retries` so a recovered node is not left buried.

The endpoint term is tiered rather than additive: a verified advertised endpoint or bridge is +2, a local-subnet or user-linked peer is +3. Local and linked peers therefore always outrank a good remote endpoint, which is what makes the mesh prefer them. A local peer that also has a verified endpoint scores 3 for the term, not 5 -- two tiers of one property, not two properties. Range is 0 to 6.

Eviction holds 100 entries, lowest priscore first with `lastSeen` as tiebreak, in a single `synchronized` method because the cap is a whole-map invariant and two concurrent inserts each seeing 101 would otherwise both evict. Everything else in the class stays lock-free. `userLinked` and `backendPinned` entries are exempt and do not count toward the cap.

**Threading**

The design assumed these structures were EventProcessor-owned and needed no locking. They are not: they are mutated from pooled `IOThread`s, `OutConnectionController` and `RetryProcessor` across 47 call sites, and the existing `seenCXIDs` comment already said as much. The store stays concurrent-collection backed and that assumption was dropped rather than acted on.

**`hv` now means the CXNET backbone**

`addNode` used to write `hv` for every peer from any source, making it an unbounded second copy of the directory -- and since `Seed.fromCurrentPeers` builds a seed from `hv`, a seed could hand out every peer the node had ever seen. `hv` now holds only EPOCH, seed-declared hv peers and registered backends, via a new `addBackend`. `registerHVPeer` and the seed-import path were repointed to it, which is what keeps the seed round-trip intact.

Everything that iterated `hv` meaning "peers I might reach" was repointed to a new `allNodes()`/`allCXIDs()`: both relay modes in `fireEvent`, the CXN fanout, the SEED_REQUEST and PEER_LOOKUP_REQUEST fallbacks, PeerFinding response collection, and the discovery cycle, which now takes the highest-priscore peers rather than hash order. The `peerBroad` relay tail collapsed from three passes -- `hv`, then `seen`, then a third over `DataContainer.localPeerAddresses` for peers known only by address -- into one, since all three are now the same store.

**Addresses moved out of DataContainer**

`recordLocalPeer`, `getLocalPeerAddress(es)`, `removeLocalPeer`, `deprioritizeFailedRoute` and `localPeerCount` moved to `PeerDirectory`, semantics intact -- including the observed-socket replacement that stops a busy peer collecting dozens of dead ephemeral ports, and the priority prepend for advertised addresses.

The `DataContainer` fields were kept, not deleted, and marked deprecated. `DataContainer` is `Serializable` and persisted, and v0.4.3 records it silently failing to load once already when Jackson saw members it should not have; removing fields would fail deserialization of containers already on disk. `PeerDirectory.importFromDataContainer` drains them once during `connect()`.

**User-linked peers**

`DataContainer.LAN` was declared and never read or written anywhere in `src` -- the feature was never implemented. It is now `PeerEntry.userLinked` with `linkPeer`/`unlinkPeer`, exempt from eviction and carrying the +3 tier, so remote churn cannot push a user's own devices out of the directory.

**Local subnet detection**

`LocalSubnets` matches real interface addresses and prefix lengths rather than RFC1918 ranges, because a range check would call a VPN peer local and CGNAT space (100.64.0.0/10) local too. CGNAT is excluded explicitly, as are loopback, point-to-point interfaces, `/32` addresses (a host route, not a segment), and interfaces whose name matches a tunnel hint. The name heuristic errs toward excluding: a missed tunnel wrongly grants +3 and lets a remote peer outrank a real LAN peer, while a wrongly excluded interface only loses a bonus. Local means cheap to reach, never trusted -- a local peer still goes through the nonce challenge.

**Priscore-bounded fanout**

CXN broadcast now targets the top `NodeConfig.fanoutTopK` (12) peers by priscore plus `fanoutRandom` (3) drawn from the rest. The random tail is not decoration: pure top-K makes the target set static, so a peer that never cracks the top K is never contacted and never earns a score that would get it there.

### Endpoint verification: identity, not reachability

An advertised tcp4 address or bridge is pinned and stable, which is why it earns a score bonus -- but advertisement is only a claim. `ENDPOINT_CHALLENGE` / `ENDPOINT_PROOF` send a nonce to the advertised address specifically, not over any known route, since a proof arriving by another path would prove the wrong thing.

Acceptance is an identity check. None of the following is evidence: a completed TCP connect, an HTTP 200 from a bridge, a well-formed reply, or any reply at all. The endpoint earns its +2 only when the proof echoes the exact nonce, the signature verifies, **and the signer is the cxID that was probed**. That last condition is the one easily left out, and the reason a signature check alone is insufficient: an advertised address may be held by a different real CX node, whose proof verifies perfectly well against its own key. Checking "a valid signature" accepts that impostor; checking "signed by the identity I probed" rejects it. The authenticated signer is `ne.p.oCXID`, which the event data payload is already verified against before the handler runs and which a relay cannot alter. Nonces are single-use, bound to the `(cxID, address)` pair, and expire after 30s, so a captured proof cannot be replayed and an address that has changed hands does not pass.

This follows a precedent already in the codebase: bridge transport negotiation probes its own health endpoint and verifies the response identity matches its own node ID rather than trusting a 200.

Endpoint health is tracked separately from node health -- every failure path drops the bonus and leaves `retries` untouched, so a dead bridge does not make a peer that is still reachable over a temp socket look dead. Re-verification is lazy: on use-and-fail, on re-advertisement of a different address, or after 15 minutes. Probes run on `EndpointVerifier`'s own pool (`NodeConfig.endpointVerifyThreads`), never on the EventProcessor; dialing a socket from the mesh thread is the failure mode `CXAppDispatcher` was added to fix.

`PeerEntry`'s endpoint fields are `@MemoryOnly`. `Node.addr` is already `@MemoryOnly`, so advertised addresses do not survive a restart, and a persisted "verified" flag would outlive the address it refers to.

### Backend map

`BackendMap` tracks up to 30 networks LRU, holding 0 to 3 priority backends each, chosen from the network's `backendSet` and ranked by the priscore of each backend's directory entry. Selected backends are pinned so the directory cannot evict one the map still points at -- without that, the 100-peer cap and the backend map contradict each other. A backend shared by two networks stays pinned when one of them drops it.

This is a routing preference, not a membership list: `CXB` scope still fans out over the authoritative `configuration.backendSet`.

### Broadcast permissions

CXN broadcast was plain fanout with no restriction at all: any peer could fan out anything to the whole mesh, CXNET included.

**Hierarchical resolution over the existing permission system**

No new permission system, and no change to storage, serialization or sync. Decompiling `BasicPermissionContainer` (Util v2) showed it already provides nearly everything needed: `Entry.getAllow()` is a native explicit deny, `allowed()` returns false on a miss so default-deny is already the behaviour, `getWeight`/`allowedRelational` exist, and grants already live in `CXNetwork.networkPermissions`, already sync in the seed, and are already mutated by the GRANT/REVOKE handlers.

What it lacked was matching. `allowed()` is an exact two-level lookup, so a stored `CXNET.broadcast.*` matches only a literal request for that string and never `CXNET.broadcast.DIRECTORY_UPDATE`. `PermissionResolver` is therefore a function over the existing container: it generates candidate keys most-specific-first (`CXNET.broadcast.DIRECTORY_UPDATE`, `CXNET.broadcast.*`, `CXNET.*`, `*`) and returns the first hit's verdict, falling back to the `DEFAULT_NODE` identity. That yields most-specific-wins, wildcards, explicit-deny-beats-a-broader-allow and default deny, generically -- `network.relay.*` and `network.directory.write` need nothing special.

`allowed()` short-circuits to true for the identity string `SYSTEM`. The existing call sites guard that with `assert !deviceID.contains("SYSTEM")`, but asserts are no-ops without `-ea`, the same defect as the v0.6.2 `NodeMesh:572` finding. The resolver uses a real runtime check.

**`broadcast` and `relay` are separate permissions**

One permission checked against one identity cannot work, in any of its three forms. Against the **origin**, a CXNET backend relaying a new peer's event is dropped, so legitimate backend relay fails. Against the **transmitter**, propagation survives exactly one hop -- the next relay holds no grant -- and authorizing authorship on the transmitter is the laundering bug the v0.6.2 administrative handlers were hardened against. **Rewriting the origin** to make a check pass fails outright: `nc.e` is signed by the origin and verified against `oD`, so a rewritten `oD` fails signature verification.

So forwarding and originating are different privileges:

- `<net>.broadcast.<type>` on the verified **origin** -- may this identity inject into the scope
- `<net>.relay.<type>` on the authenticated **transmitter** -- may this identity forward one

Relay is safe to grant broadly, since a relay can only forward something already authorized at its origin and the dedup map bounds amplification; revoking it for one identity remains the lever for killing a misbehaving relay. Nothing is rewritten: the origin signs the inner event once, relays re-sign only the container, exactly as the protocol already worked.

**Origin identity**

Taken from `ne.p.oCXID`, which lives inside the signed event and so cannot be touched by a relay, cross-checked against the container's `oD`, dropping on mismatch. A null `oD` on a broadcast is refused rather than defaulted: in honest flow `oD` is always set at origin and preserved in every relay branch, so a null is anomalous, and defaulting to the transmitter would make this check disagree with what the signature was verified against. No "relayed" flag was added -- it is derivable as `oD != iD`, and a flag would be attacker-controlled where the comparison is backed by the verified container signature.

**Defaults**

CXNET is closed: only its `backendSet`, the NMI and EPOCH hold `CXNET.broadcast.*`. Every other network grants `<net>.broadcast.*` to `DEFAULT_NODE` by default (`Configuration.openBroadcast`, default true), so a pure-p2p mesh like CXChat behaves exactly as the previous unrestricted fanout did, with multihop intact and no cosigning needed. `<net>.relay.*` is granted to `DEFAULT_NODE` everywhere, CXNET included. Closed-by-default applies to CXNET alone and does not leak into member networks, and being per-network state it stays revocable through the ordinary GRANT/REVOKE path.

**Enforcement is off by default**

`NodeConfig.enforceBroadcastPermissions` defaults to false and `broadcastPermissionAudit` to true, so every decision that *would* have dropped is logged with its origin identity and event type instead of dropping anything. CXN broadcast has been unrestricted until now and bootstrap traffic rides it, so enforcing on first run risks a mesh that cannot bootstrap; the audit log from a live bootstrap produces the real grant set. The origin-per-event-type pairing is also what decides whether cosigned events are needed -- an event type broadcast only by backends needs nothing further.

Resolved `(identity, node)` verdicts are cached per network and cleared on any governance change: both grant handlers, and zero-trust activation in both of its paths. A stale allow outliving a revocation is the one direction that must never happen. The cache field is `transient` and `@JsonIgnore`, lazily created and null-checked on every use, because `CXNetwork` is Jackson-deserialized when a network arrives in a seed.

**Ordering deviation**

The design called for the cheap transmitter check to run before signature verification, as a DoS filter. That is not achievable without restructuring `processNetworkInput`, which verifies the container at `:278` and the inner event at `:542`, both before reaching dedup at `:664`. Both checks therefore run on already-verified data: correct, but without the cheap-first saving. The relay check also deliberately does not apply on the "unknown sender path accepted" branch, where `nc.iD` is unverified and the check would be bypassed by taking that path.

### Event dedup eviction order

The cleanup in `processNetworkInput` called itself "simple FIFO" but iterated a `ConcurrentHashMap` keySet, whose order is a function of hash and table layout, not arrival. It could evict an event ID seen moments ago while keeping one far older, letting a just-dropped event be reprocessed and relayed again. Arrival order is now tracked explicitly in a queue and eviction is genuinely oldest-first. Entries are still not refreshed on a duplicate hit -- refreshing would let a flood keep its own IDs alive indefinitely by continuing to resend them.

### Not covered

**TTL is still dead.** `NetworkContainer.ttl` is documented for decrement-per-relay and drop-at-zero but has no reads or writes anywhere in `src`, and was left that way. A hop count is set by the origin, so a hostile node sends `ttl = 500` and the limit bounds nothing; it only constrains well-behaved senders. A time-based bound is the right primitive, and to resist the same attack the timestamp has to sit inside the *signed event* rather than the per-hop container (`Node.signedAt` is the precedent). That is CXPath work. Consequence accepted knowingly: the event dedup map remains the only loop protection, which is the status quo rather than a regression.

**Cosigned events: coming in a following release.** The cosigned-blob flow lets a backend originate a broadcast carrying a new peer's identity, which covers bootstrap, but only for event types whose content *is* an identity blob. For a peer's own `MESSAGE` or a plugin `SYNC`, a backend cannot originate without misattributing authorship.

The model is that a cosigned event **uses the cosigner's transport permissions**: authorship stays entirely with the origin, which signs the inner event as it does today, while the cosigner lends the event its own `broadcast`/`relay` standing. The rule becomes "origin holds broadcast, or a verified cosigner does". Mechanically this is an outer cosignature over `nc.e` carried in sibling `NetworkContainer` fields rather than an extra wrapping layer, so the `:542` verification is untouched; and because scope, network and event ID already live inside the signed payload, signing `nc.e` binds the cosignature to exactly one scope and one event for free, with no replay onto another event and no lifting from one network to another.

The audit run settled the open question of whether this is needed. It is. Across a four-peer `MultiPeerTest`, 27 refusals were logged, all under enforcement-off: 19 `MESSAGE`, 4 `PeerFinding` and 4 `NewNode` refused as "origin holds no broadcast grant", every one of them originated by an **ordinary peer rather than a backend**, plus 2 refused as "transmitter holds no relay grant" where the transmitter was EPOCH itself. `MESSAGE` authored by an arbitrary member is precisely the case a backend cannot re-originate on its behalf, so closing CXNET requires either this mechanism or a standing grant for those event types.

**Nothing in this release breaks as a result.** `enforceBroadcastPermissions` is false, so every refusal above is a `WOULD-DROP` log line and no event is dropped; `MultiPeerTest` passes 11/11 with the audit active. Transport behaviour is unchanged from the previous release.

**Old bootstraps will need updating in a future release.** The grant seeding runs in `createNetwork`, so it only applies to networks created by this code. A network that arrives in a seed carries whatever `permissionSet` its original creator built, and for a CXNET created before this change that contains no `broadcast` or `relay` entries at all -- which is why EPOCH itself shows as holding no relay grant above. Enabling enforcement against such a seed would deny everything on CXNET, EPOCH's own relays included. Before enforcement can be turned on, CXNET's `permissionSet` has to gain `CXNET.broadcast.*` for EPOCH and the backendSet and `CXNET.relay.*` for `DEFAULT_NODE`, applied either by GRANT_PERMISSION from the NMI or by republishing the CXNET seed from current code. That migration is deliberately not part of this release.

**Mixed-version meshing.** A peer on an older jar that receives `ENDPOINT_CHALLENGE` hits the unknown-event path, which records a Tear analytic and logs "UNABLE TO PROCESS UNKNOWN EVENT". It is survivable -- all three `EventType.valueOf` call sites are try-guarded and the socket is not torn down there -- but it is noise, and that peer's endpoints stay unverified so it scores on availability alone until its jar is updated. Seed compatibility is unaffected: `FAIL_ON_UNKNOWN_PROPERTIES` is false, so an older node ignores `Configuration.openBroadcast`, and the new permission entries are ordinary map keys it simply never consults. `EndpointChallenge` falls under the existing `us.anvildevelopment.cxnet.` polymorphic-type allowlist.

**Verification.** 58 unit tests cover priscore arithmetic and tier non-stacking, eviction order and exemptions, address semantics, the projections, resolver specificity/wildcards/deny-precedence/`DEFAULT_NODE`/plugin namespacing/`SYSTEM` refusal, and the endpoint negative cases -- including a valid proof from the wrong identity, a replayed proof, and a bare accept with no proof. These are inert by construction: no `peersDir` is set, so no disk, no sockets, no bootstrap.

`MultiPeerTest` passes 11/11 (four peers, 145s), including E2E encryption, signed message delivery and stream sessions, with the permission audit active. Peer state settles at `peers=18 backbone=1 with-addresses=4-5` per peer and no unreachable peers, confirming the collapsed store and the narrowed `hv` behave under real discovery.

`BootstrapServerTest` was not run: it stands up EPOCH against the same working directory as the live prod/test node, so it stays operator-run with EPOCH stopped. It is also the one test that exercises the `hv`-only registration path fixed below, so it is the first thing to run once a window allows.

**One regression found and fixed during that run.** `getAllAddresses` is where every targeted send resolves its routes -- CXS, E2E and streams all pass through it -- and the original searched four maps for `Node.addr`, `hv` among them. The rewrite returned early when a cxID had no `PeerEntry`, so a node registered straight into `hv` with no directory entry resolved to zero addresses and became unreachable. `BootstrapServerTest` registers EPOCH exactly that way. `MultiPeerTest` never caught it, because CXHELLO discovery routes through `addNode` and EPOCH through `addBackend`, both of which create a `PeerEntry`. Fixed by consulting `hv` unconditionally alongside the directory entry, and covered by two tests: one asserting an `hv`-only peer still resolves an address, one asserting the address is not duplicated when both sources hold it.

`CXPeer4Test` was also added as a single-peer launcher for manual runs: its own root and ports (49160/8090, clear of PEER2 and PEER3), one message to EPOCH, then periodic reporting of the ranked peer store with priscores, fanout target count, outstanding endpoint probes, preferred backends and the resolved broadcast/relay verdicts per network.
