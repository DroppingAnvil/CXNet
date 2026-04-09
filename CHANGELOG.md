# Changelog

## Unreleased

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
