# ConnectX

[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=DroppingAnvil_CXNet&metric=alert_status)](https://sonarcloud.io/project/overview?id=DroppingAnvil_CXNet)
[![Maven](https://img.shields.io/badge/maven-0.4.3--SNAPSHOT-blue)](https://repo.anvildevelopment.us/repository/maven-public/)

> **Early Development.** Core networking, encryption, and event API are functional and tested. Blockchain sync, Zero Trust activation, and remote directory are partially implemented.

A decentralized P2P mesh network framework with pluggable cryptography, serialization, and transports. Every event at every hop is signed or encrypted by the originating node before it leaves the process.

**[CXNexus](https://AnvilDevelopment.us/cxnexus)** is a desktop application built on ConnectX as a proof of concept, featuring a live node dashboard, peer and network management, and CXChat: decentralized E2E encrypted messaging. Coming soon.

---

## How it works

Each node participates in **CXNET**, the global bootstrap network. Private networks (`CXNetwork`) run on top of CXNET with their own identity, permissions, and blockchain. Every network is governed by a **Network Master Identity (NMI)** that provisions nodes and manages permissions. Zero Trust mode permanently locks the trust structure when activated.

Events flow through a [5-stage concurrent pipeline](CX-PROTOCOL.md#threading-model): SocketWatcher → IOThread (signature verify) → EventProcessor (logic + decrypt) → OutputProcessor (sign + route) → RetryProcessor. Signing and verification run on two independent 4-thread pools so ingress never blocks egress.

A [three-layer signature chain](CX-PROTOCOL.md#three-layer-encryption-system) covers every message:

1. **NetworkContainer** — hop signature, added and verified at each relay node
2. **NetworkEvent** — origin signature, set by the sender and preserved through the entire relay chain
3. **NetworkEvent.d** — payload, signed or E2E encrypted depending on the event type

---

## Features

- **Pluggable crypto, serialization, and transports.** Swap PGP for another scheme, Jackson for another serializer, or HTTP bridge for another transport without touching protocol logic.
- **Managed network governance.** NMI provisions nodes, controls permissions, and issues Integration Keys (CXIK) for network registration. Zero Trust mode locks the structure permanently.
- **Stream sessions.** Bidirectional encrypted channels via `CXStreamPlugin`, TCP or WebSocket with automatic bridge negotiation.
- **Fluent event API.** `buildEvent().toPeer().signData().queue()`
- **HTTP bridge.** Punch through firewalls and NAT with no open port required on the connecting side.
- **LAN discovery.** Automatic peer discovery on startup via CXHELLO.
- **3-chain blockchain per network.** Admin (`c1`), Resources (`c2`), Events (`c3`).
- **Per-instance design.** Run multiple independent nodes in the same JVM.

See [`CX-PROTOCOL.md`](CX-PROTOCOL.md) for the full protocol specification.

---

## Installation

Maven:

```xml
<repositories>
  <repository>
    <id>AnvilDevelopment</id>
    <url>https://repo.anvildevelopment.us/repository/maven-public/</url>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>us.anvildevelopment</groupId>
    <artifactId>ConnectX</artifactId>
    <version>0.4.3-SNAPSHOT</version>
  </dependency>
</dependencies>
```

Gradle:

```groovy
repositories {
    maven { url 'https://repo.anvildevelopment.us/repository/maven-public/' }
}

dependencies {
    implementation 'us.anvildevelopment:ConnectX:0.4.3-SNAPSHOT'
}
```

---

## Quick Start

```java
ConnectX peer = new ConnectX("CX-PEER1", 49153, "03006000-0400-0500-0000-007000000001", "Peer1");
peer.updateHTTPBridgePort(8081);
peer.setPublicBridgeAddress("cxHTTP1", "https://cx1.anvildevelopment.us/cx");
peer.connect(49153);
```

Send a message:

```java
peer.buildEvent(EventType.MESSAGE, "Hello!".getBytes())
    .toPeer("target-uuid")
    .signData()
    .queue();
```

Receive messages with a plugin:

```java
peer.addPlugin(new CXMessagePlugin() {
    public void onMessage(String senderID, CXMessage message) {
        System.out.println(senderID + ": " + message.text);
    }
});
```

> MESSAGE payloads must be sent as `CXMessage` with `.signData()` or `.encrypt(recipientID)`. Raw unsigned payloads are rejected at the receiver.

---

## Routing

```java
// Peer-to-peer (CXS)
peer.buildEvent(EventType.MESSAGE, data).toPeer(targetID).signData().queue();

// Network broadcast (CXN)
peer.buildEvent(EventType.MESSAGE, data).toNetwork("CXNET").signData().queue();

// Explicit bridge
peer.buildEvent(EventType.MESSAGE, data).viaBridge("cxHTTP1", "https://example.com/cx").signData().queue();
```

Failed CXS deliveries retry with exponential backoff and promote to CXN broadcast after `CXS_TO_CXN_THRESHOLD` failures (default: 4). See [routing details](CX-PROTOCOL.md#event-delivery-lifecycle).

---

## Plugin System

Plugins intercept events by service name. Three data levels control what is delivered:

| `DataLevel` | Receives |
|---|---|
| `NETWORK_EVENT` | Raw `NetworkEvent` (default) |
| `INPUT_BUNDLE` | Full `InputBundle` (signed bytes, container) |
| `OBJECT` | Deserialized typed object via `plugin.type` |

```java
CXPlugin plugin = new CXPlugin("MESSAGE") {{
    dataLevel = DataLevel.OBJECT;
    type = MyMessage.class;
}};
peer.addPlugin(plugin);
```

---

## Network Architecture

```
CXNET (Global Bootstrap Network)
  └── CXNetwork  (e.g. "TESTNET", "CXChat")
        ├── NMI  (Network Master Identity)
        ├── backendSet  (trusted infrastructure nodes)
        └── Peer nodes
```

Each node has its own `cxRoot` directory containing its keypair, bootstrap seed, per-network seeds, blockchain data, and local state (`data.cxd`). See [network architecture](CX-PROTOCOL.md#network-architecture) for full details.

---

## Port Reference

| Port | Purpose |
|---|---|
| `49152` | Default P2P port (EPOCH bootstrap node) |
| `49153-49162` | Standard peer P2P range |
| `8080` | Default HTTP bridge port |
| `8081+` | HTTP bridge ports for additional peers |

LAN discovery scans `49152-49162`. Peers outside that range must be reached via HTTP bridge.

---

## Project Structure

```
src/main/java/us/anvildevelopment/cxnet/
  ConnectX.java             Core API entry point
  api/                      Plugin interfaces (CXPlugin, CXMessagePlugin, DataLevel)
  network/                  CXNetwork, InputBundle, Seed, event system
  network/nodemesh/         NodeMesh, PeerDirectory, bridges
  network/events/           Typed event payloads (CXMessage, CXHello, PeerFinding, ...)
  crypt/                    CryptProvider abstraction + PGPainless implementation
  edge/                     DataContainer, ConnectXClient
src/test/java/
  MultiPeerTest.java        Full multi-peer integration test
  BootstrapServerTest.java  EPOCH bootstrap + CXNET seed test
```

---

## Documentation

- [`CX-PROTOCOL.md`](CX-PROTOCOL.md) — full protocol spec: encryption layers, threading model, blockchain, event types, permissions, Zero Trust
- [`CHANGELOG.md`](CHANGELOG.md) — release history

---

*Copyright (c) 2026 Christopher Willett. All Rights Reserved.*