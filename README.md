# ConnectX

[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=DroppingAnvil_CXNet&metric=alert_status)](https://sonarcloud.io/project/overview?id=DroppingAnvil_CXNet)
[![Maven](https://img.shields.io/badge/maven-0.4.1-blue)](https://repo.anvildevelopment.us/repository/maven-releases/)

> **Early Development - Work in Progress**
> The core networking, encryption, and event API are functional and tested. Many subsystems (blockchain sync, Zero Trust activation, LAN discovery, resource management, login, remote directory) are partially or not yet implemented.

**[CXNexus](https://AnvilDevelopment.us/cxnexus)** is a Windows desktop application being built on CXNet as a proof of concept for the protocol. It includes a live node dashboard, peer and network management, and CXChat: decentralized E2E encrypted messaging with no central server. Coming soon.

A decentralized P2P mesh network framework built around pluggable cryptography, serialization, and transports. Every event at every hop is signed or encrypted by the originating node before it leaves the process. The crypto, serialization, and bridge layers are all swappable interfaces so implementations can be replaced as better options become available.

Each network is governed by a Network Master Identity (NMI) that provisions nodes and manages permissions. Zero Trust mode permanently removes the NMI's ability to modify the trust structure when activated.

**CXNET** is the global bootstrap network. Private networks (`CXNetwork`) run on top of it with their own identity, permissions, and blockchain.

---

## Features

* **Three-layer signature chain** - hop signature (NetworkContainer), origin signature (NetworkEvent), optional E2E payload encryption. Tampering at any layer drops the message.
* **Pluggable crypto, serialization, and bridges** - swap implementations without touching protocol logic
* **Managed network governance** - NMI provisions nodes and controls permissions. Zero Trust mode locks the structure permanently when activated.
* **Stream sessions** - bidirectional encrypted channels via `CXStreamPlugin`, TCP or WebSocket with automatic bridge negotiation
* **Fluent event API** - `buildEvent().toPeer().signData().queue()`
* **Concurrent crypto pipeline** - signing and verification across two independent 4-thread pools, ingress never blocks egress
* **HTTP bridge** - punch through firewalls and NAT, no open port required on the connecting side
* **LAN discovery** - automatic peer discovery via CXHELLO
* **3-chain blockchain** per network - Admin (`c1`), Resources (`c2`), Events (`c3`)
* **Per-instance design** - run multiple independent nodes in the same JVM

---

## Installation

Maven:

```xml
<repositories>
  <repository>
    <id>Sonatype</id>
    <url>https://repo.anvildevelopment.us/repository/maven-releases/</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>us.anvildevelopment</groupId>
    <artifactId>ConnectX</artifactId>
    <version>0.4.1</version>
  </dependency>
</dependencies>
```

Gradle:

```groovy
repositories {
    maven { url 'https://repo.anvildevelopment.us/repository/maven-releases/' }
}

dependencies {
    implementation 'us.anvildevelopment:ConnectX:0.4.1'
}
```

---

## Quick Start

```java
ConnectX peer = new ConnectX("CX-PEER3", 49158, "03006000-0400-0500-0000-007000000001", "Peer3");
peer.updateHTTPBridgePort(8081);
peer.setPublicBridgeAddress("cxHTTP1", "https://cx7.anvildevelopment.us/cx");
peer.buildEvent(EventType.MESSAGE, "Hello peer1!".getBytes()).toPeer("00000000-0000-0000-0000-000000000001").signData().queue();
```

The constructor handles key generation, filesystem setup, HTTP bridge registration, and network connection automatically.

Receive messages with a plugin:

```java
peer.addPlugin(new CXMessagePlugin() {
    public void onMessage(String senderID, CXMessage message) {
        System.out.println(senderID + ": " + message.text);
    }
});
```

**Note:** MESSAGE payloads must be sent as `CXMessage` with `.signData()` or `.encrypt(recipientID)`. Raw unsigned payloads are rejected by NodeMesh signature verification.

---

## Routing

The `EventBuilder` supports three routing modes:

```java
// Peer-to-peer (CXS)
peer.buildEvent(EventType.MESSAGE, data).toPeer(targetID).signData().queue();

// Network broadcast (CXN)
peer.buildEvent(EventType.MESSAGE, data).toNetwork("CXNET").signData().queue();

// Explicit bridge
peer.buildEvent(EventType.MESSAGE, data).viaBridge("cxHTTP1", "https://example.com/cx").signData().queue();
```

Failed CXS deliveries are retried with exponential backoff. After `CXS_TO_CXN_THRESHOLD` failures (default: 4) the event is promoted to a CXN broadcast with E2E encryption. After `MAX_RETRIES` total attempts (default: 50) the event is discarded.

---

## Plugin System

Plugins intercept events by service name (matching `EventType`). Three data levels control what is passed to `handleEvent`:

| `DataLevel` | Receives |
|---|---|
| `NETWORK_EVENT` | Raw `NetworkEvent` (default) |
| `INPUT_BUNDLE` | Full `InputBundle` - signed bytes, container |
| `OBJECT` | Deserialized typed object via `plugin.type` |

```java
CXPlugin plugin = new CXPlugin("MESSAGE") {{
    dataLevel = DataLevel.OBJECT;
    type = MyMessage.class;
}};
peer.addPlugin(plugin);
```

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

## Architecture

```
CXNET (Global Bootstrap Network)
  |-- CXNetwork  (e.g. "TESTNET", "MyApp")
       |-- NMI  (Network Master Identity)
       |-- Backend nodes  (trusted infrastructure, priority routing)
       |-- Peer nodes  (regular participants)
```

Each node runs a 5-stage concurrent pipeline: SocketWatcher, IOThread pool (sig verify), EventProcessor (logic + decrypt), OutputProcessor pool (sign + route), RetryProcessor. See [`CX-PROTOCOL.md`](CX-PROTOCOL.md) for the full pipeline and threading breakdown.

[Interactive architecture analysis (SonarCloud)](https://sonarcloud.io/project/architecture/discovery?id=DroppingAnvil_CXNet&selectedNode=dev.droppinganvil.v3.ConnectX)

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
ConnectX-EPOCH/             EPOCH NMI node data (local, not committed)
ConnectX-Peer{1-5}/         Test peer runtime directories
```

---

## Documentation

* [`CX-PROTOCOL.md`](CX-PROTOCOL.md) - full protocol spec: encryption layers, threading model, blockchain, event types, permissions, Zero Trust
* [`CHANGELOG.md`](CHANGELOG.md) - release history

---

## Built on ConnectX

**[CXNexus](https://AnvilDevelopment.us/cxnexus)** - a messaging platform built on top of CXNet. Coming soon.

---

*Copyright (c) 2026 Christopher Willett. All Rights Reserved.*
