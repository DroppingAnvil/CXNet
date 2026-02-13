# ConnectX

> **Early Development — Work in Progress**
> The core networking, encryption, and event API are functional and tested. Many subsystems (blockchain sync, Zero Trust activation, LAN discovery, resource management, login, remote directory) are partially or not yet implemented.

A decentralized P2P mesh network framework with end-to-end PGP encryption, blockchain-based event persistence, and a fluent Java API.

---

## Quick Start

### Spin up a peer and send a signed message — 4 lines

```java
ConnectX peer = new ConnectX("CX-PEER3", 49158, "03006000-0400-0500-0000-007000000001", "Peer3");
peer.updateHTTPBridgePort(8081);
peer.setPublicBridgeAddress("cxHTTP1", "https://cx7.anvildevelopment.us/cx");
peer.buildEvent(EventType.MESSAGE, "Hello network!".getBytes()).toPeer("00000000-0000-0000-0000-000000000001").signData().queue();
```

### Receive messages with a plugin — 3 lines

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

ConnectX (CX) is a peer-to-peer mesh network protocol and Java framework built for distributed applications. Each node connects directly to peers, routes events through a mesh, signs every transmission with PGP, and optionally persists events to a per-network blockchain — all without a central server.

**CXNET** is the global bootstrap network. Private networks (`CXNetwork`) run on top of it with their own identity, permissions, and blockchain.

---

## Features

- **Fluent event API** — `buildEvent().toPeer().signData().queue()`
- **PGP encryption at every layer** — transport (hop-by-hop) and end-to-end
- **HTTP bridge** — punch through firewalls, no open port required
- **LAN discovery** — automatic peer discovery on local networks via CXHELLO
- **3-chain blockchain** per network — Admin (`c1`), Resources (`c2`), Events (`c3`)
- **Zero Trust mode** — irreversible decentralization, NMI relinquishes control
- **Plugin system** — extend with `CXPlugin` / `CXMessagePlugin` for custom event handling
- **Per-instance design** — run multiple independent nodes in the same JVM

---

## Architecture

```
CXNET (Global Bootstrap Network)
  └── CXNetwork  (e.g. "TESTNET", "MyApp")
       ├── NMI  (Network Master Identity — creates/controls the network)
       ├── Backend nodes  (trusted infrastructure, priority routing)
       └── Peer nodes  (regular participants)
```

Each node runs:
- **NodeMesh** — peer connections, event routing, PeerDirectory
- **CXNetwork(s)** — one or more logical networks with independent blockchains
- **CryptProvider** — PGP key management and signing (PGPainless)
- **HTTP bridge** — Jetty-based servlet for internet-reachable peers
- **Plugin registry** — application-level event handlers

---

## Plugin System

Plugins intercept events by service name (matching `EventType`). Three data levels control what is passed to `handleEvent`:

| `DataLevel`     | Receives                                      |
|-----------------|-----------------------------------------------|
| `NETWORK_EVENT` | Raw `NetworkEvent` (default)                  |
| `INPUT_BUNDLE`  | Full `InputBundle` — signed bytes, container  |
| `OBJECT`        | Deserialized typed object via `plugin.type`   |

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
peer.buildEvent(EventType.MESSAGE, data).toNetwork("CXNET").queue();

// Explicit bridge
peer.buildEvent(EventType.MESSAGE, data).viaBridge("cxHTTP1", "https://example.com/cx").queue();
```

---

## Protocol Documentation

See [`ConnectXBackend/CX-PROTOCOL.md`](ConnectXBackend/CX-PROTOCOL.md) for the full protocol specification — encryption layers, blockchain structure, event types, permission system, Zero Trust mode, and consensus mechanism.

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