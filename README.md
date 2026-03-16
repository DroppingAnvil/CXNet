# ConnectX

> **Early Development - Work in Progress**
> The core networking, encryption, and event API are functional and tested. Many subsystems (blockchain sync, Zero Trust activation, LAN discovery, resource management, login, remote directory) are partially or not yet implemented.

A decentralized P2P mesh network framework with end-to-end PGP encryption, blockchain-based event persistence, and a fluent Java API.

---

## Quick Start

### Spin up a peer and send a signed message - 4 lines

```java
ConnectX peer = new ConnectX("CX-PEER3", 49158, "03006000-0400-0500-0000-007000000001", "Peer3");
peer.updateHTTPBridgePort(8081);
peer.setPublicBridgeAddress("cxHTTP1", "https://cx7.anvildevelopment.us/cx");
peer.buildEvent(EventType.MESSAGE, "Hello network!".getBytes()).toPeer("00000000-0000-0000-0000-000000000001").signData().queue();
```

### Receive messages with a plugin - 3 lines

```java
peer.addPlugin(new CXMessagePlugin() {
    public void onMessage(String from, String message) {
        System.out.println(from + ": " + message);
    }
});
```

The constructor handles key generation, filesystem setup, HTTP bridge registration, and network connection automatically. The `buildEvent` fluent API covers signing, routing, and queuing in a single chain.

---

## What is ConnectX?

ConnectX (CX) is a peer-to-peer mesh network protocol and Java framework built for managed decentralized networks. Each node connects directly to peers, routes events through a mesh, signs every transmission with PGP, and optionally persists events to a per-network blockchain, all without a central server.

**CXNET** is the global bootstrap network. Private networks (`CXNetwork`) run on top of it with their own identity, permissions, and blockchain.

---

## Security Model

ConnectX treats cryptographic identity as the sole basis for trust at the protocol level. Every message, event, and state transition is signed or encrypted by the originating peer before it touches the wire. This is not defense-in-depth. It is exploit-class elimination.

**What this means in practice:** Traditional exploit vectors like use-after-free, buffer overflows, or data races that corrupt in-memory state cannot escalate into protocol-level attacks. If memory corruption mangles a message in transit, it does not become a privilege escalation. It becomes an invalid signature. The data is either authentically from who it claims to be from, or it gets rejected. There is no middle ground where corrupted data gets treated as legitimate.

The protocol enforces this through a **three-layer signature and encryption system**: transport-level signing on every hop (NetworkContainer), message-level signing by the original sender (NetworkEvent), and payload-level encryption for application data. Any tampering at any layer breaks the signature chain and the data is dropped.

The entire attack surface collapses to one well-understood problem: key compromise. CXNet does not pretend to prevent that. No system can. What it does is eliminate every other path to network-level damage, and then gives network owners the governance tools to manage the reality of key compromise.

**Managed network governance:** The Network Master Identity (NMI) provisions nodes, assigns permissions through an embedded permissions framework, and configures the trust structure. Ideally nodes operate at equal authority levels within the permission model. When the network owner is ready, Zero Trust mode can be activated, an irreversible decision that permanently locks the trust structure and removes even the NMI's ability to modify it.

**Honest about what cryptography can and cannot do:** For real data networks (not cryptocurrency where you can mathematically verify value), there is no way to determine whether an authenticated action was intended or not. If a node's key is compromised and the attacker sends valid signed messages, those are authentic as far as the network is concerned. Same way a stolen badge gets you through a door. The network cannot read minds. What it can guarantee is that nobody gets through the door without a badge, which is the part most systems actually fail at.

The same trust boundary every secure system ultimately relies on. CXNet just removes all the other ways to cheat.

---

## Architecture

```
CXNET (Global Bootstrap Network)
  |-- CXNetwork  (e.g. "TESTNET", "MyApp")
       |-- NMI  (Network Master Identity - creates/controls the network)
       |-- Backend nodes  (trusted infrastructure, priority routing)
       |-- Peer nodes  (regular participants)
```

Each node runs:

* **NodeMesh** - peer connections, event routing, PeerDirectory
* **CXNetwork(s)** - one or more logical networks with independent blockchains
* **CryptProvider** - PGP key management and signing (PGPainless)
* **HTTP bridge** - Jetty-based servlet for internet-reachable peers
* **Plugin registry** - application-level event handlers

---

## Threading Model

ConnectX routes every message through a **5-stage concurrent pipeline**. Each stage runs on its own dedicated thread or thread pool. Incoming data never blocks event processing, and the heaviest cryptographic work is split across two independent 4-thread pools: one on ingress and one on egress.

```
Wire
 |
 v
SocketWatcher (1 thread)
  Accept TCP connection, read into 8 KB buffer, wrap in NetworkInputIOJob,
  push to jobQueue.
 |
 v
IOThread pool (4 threads)  <-- first crypto burst
  Pull from jobQueue.
  Strip + verify NetworkContainer signature (Layer 1).
  Verify NetworkEvent signature (Layer 2).
  Duplicate detection, unknown-sender policy.
  Produce InputBundle, push to eventQueue.
 |
 v
EventProcessor (1 thread)  <-- logic layer
  Pull from eventQueue.
  E2E decrypt payload if encrypted (Layer 3).
  Verify payload data signature (Layer 4).
  Permission and whitelist enforcement.
  Route to NodeMesh event handlers (CXHELLO, NewNode, MESSAGE, etc.).
  Plugin dispatch.
  Produce OutputBundle(s), push to outputQueue.
 |
 v
OutputProcessor pool (4 threads)  <-- second crypto burst
  Pull from outputQueue.
  Sign NetworkEvent.
  Sign NetworkContainer.
  Route: CXS (single peer) or CXN (mesh broadcast).
  Transmit via direct socket or HTTP bridge.
  On failure: RetryBundle pushed to retryQueue.
 |
 v
RetryProcessor (1 thread)
  Exponential backoff retry.
  Falls back from CXS to CXN with E2E encryption after repeated failure.
 |
 v
Wire
```

**Why this design matters for multilayer cryptography:** PGP signing and verification are CPU-bound operations. Splitting them across two pools means 4 threads verify incoming signatures concurrently while 4 others sign outgoing events, so neither direction blocks the other. The single-threaded EventProcessor between them means all event handling and state transitions are serialized, eliminating data races without locks.

This is meaningfully different from frameworks that bolt encryption onto an existing message bus. Here the pipeline stages exist specifically to distribute cryptographic cost across cores and keep throughput high even when every message carries multiple signature layers.

| Stage | Threads | Queue | Key work |
|---|---|---|---|
| SocketWatcher | 1 | -> `jobQueue` | TCP accept, buffer read |
| IOThread pool | 4 | `jobQueue` -> `eventQueue` | Sig verify (Layers 1-2), deserialize |
| EventProcessor | 1 | `eventQueue` -> `outputQueue` | Decrypt, sig verify (Layers 3-4), logic |
| OutputProcessor pool | 4 | `outputQueue` -> `retryQueue` | Sign, route, transmit |
| RetryProcessor | 1 | `retryQueue` | Backoff retry |

All inter-stage communication uses `ConcurrentLinkedQueue`. Thread counts are configurable via `NodeConfig`.

---

## Plugin System

Plugins intercept events by service name (matching `EventType`). Three data levels control what is passed to `handleEvent`:

| `DataLevel` | Receives |
|---|---|
| `NETWORK_EVENT` | Raw `NetworkEvent` (default) |
| `INPUT_BUNDLE` | Full `InputBundle` - signed bytes, container |
| `OBJECT` | Deserialized typed object via `plugin.type` |

```java
// Custom typed plugin example
CXPlugin plugin = new CXPlugin("MESSAGE") {{
    dataLevel = DataLevel.OBJECT;
    type = MyMessage.class;
}};
peer.addPlugin(plugin);
```

For plain text messages, extend `CXMessagePlugin` as shown in the Quick Start above.

---

## Routing

The `EventBuilder` supports three routing modes:

```java
// Peer-to-peer
peer.buildEvent(EventType.MESSAGE, data).toPeer(targetID).signData().queue();

// Network broadcast
peer.buildEvent(EventType.MESSAGE, data).toNetwork("CXNET").signData().queue();

// Explicit bridge
peer.buildEvent(EventType.MESSAGE, data).viaBridge("cxHTTP1", "https://example.com/cx").signData().queue();
```

---

## Port Reference

| Port | Purpose |
|---|---|
| `49152` | Default P2P port (`connect()` no-arg, EPOCH bootstrap node) |
| `49153-49162` | Standard peer P2P range (increment per node) |
| `8080` | Default HTTP bridge port (bootstrap/EPOCH) |
| `8081+` | HTTP bridge ports for additional peers |

**P2P constructor:** the second argument is the P2P listening port.

```java
new ConnectX("CX-PEER2", 49153, cxID, password); // P2P on 49153
peer.updateHTTPBridgePort(8081);                  // HTTP bridge on 8081
```

**LAN discovery scope:** CXHELLO (LAN peer discovery) scans the range `49152-49162` plus a few alternate ranges. If a node binds to a port outside this range, LAN discovery will not find it. Peers outside the scanned range must be reached via an HTTP bridge. Use `setPublicBridgeAddress()` on the target node and `updateHTTPBridgePort()` to expose one.

**Internet peers:** Direct P2P connections require the remote port to be reachable (open firewall/port forward). If that is not possible, use the HTTP bridge. It works through firewalls and NAT with no open port required on the connecting side.

---

## Features

* **Fluent event API** - `buildEvent().toPeer().signData().queue()`
* **PGP encryption at every layer** - transport (hop-by-hop) and end-to-end
* **Concurrent crypto pipeline** - 4-thread ingress and 4-thread egress pools distribute signing/verification across cores
* **Pluggable cryptography** - PGPainless default, fully swappable via abstract `CryptProvider`
* **HTTP bridge** - punch through firewalls, no open port required
* **LAN discovery** - automatic peer discovery on local networks via CXHELLO
* **3-chain blockchain** per network - Admin (`c1`), Resources (`c2`), Events (`c3`)
* **Zero Trust mode** - irreversible decentralization, NMI relinquishes control
* **Plugin system** - extend with `CXPlugin` / `CXMessagePlugin` for custom event handling
* **Per-instance design** - run multiple independent nodes in the same JVM

---

## Protocol Documentation

See [`CX-PROTOCOL.md`](CX-PROTOCOL.md) for the full protocol specification covering encryption layers, blockchain structure, event types, permission system, Zero Trust mode, and consensus mechanism.

---

## Project Structure

```
ConnectXBackend/          Main framework (Maven)
  src/main/java/dev/droppinganvil/v3/
    ConnectX.java         Core API entry point
    api/                  Plugin interfaces (CXPlugin, CXMessagePlugin, DataLevel)
    network/              CXNetwork, InputBundle, event system
    network/nodemesh/     NodeMesh, PeerDirectory, bridges
    crypt/                CryptProvider abstraction + PGPainless implementation
    edge/                 DataContainer, ConnectXClient
    test/                 CXPeer1-3Test, HTTPBridgeTest, MultiPeerTest
ConnectX-EPOCH/           EPOCH NMI node data (local, not committed)
ConnectX-Peer{1-5}/       Test peer runtime directories
```

---

*Copyright (c) 2026 Christopher Willett. All Rights Reserved.*
