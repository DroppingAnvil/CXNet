# ConnectX (CX) Protocol Documentation

**Version:** 3.0
**Last Updated:** 2025-01-12

---

## Table of Contents
1. [Overview](#overview)
2. [Network Architecture](#network-architecture)
3. [Encryption & Security](#encryption--security)
4. [Routing & Transmission](#routing--transmission)
5. [Blockchain Structure](#blockchain-structure)
6. [Event System](#event-system)
7. [Permissions & Access Control](#permissions--access-control)
8. [Implementation Notes](#implementation-notes)

---

## Overview

ConnectX (CX) is a decentralized peer-to-peer (P2P) mesh network protocol featuring:
- **Blockchain-based event persistence** (3-chain architecture per network)
- **Multi-layer PGP encryption and signing** (transport, message, payload)
- **Flexible routing** with priority infrastructure nodes
- **Permission-based access control** with weighted entries
- **Privacy-preserving design** (no node-network association data)
- **Cross-network communication** capabilities

---

## Network Architecture

### Network Hierarchy

```
CXNET (Global Network)
  └── CXNetwork (Individual Networks, e.g., "TESTNET", "AnvilDevelopment")
       ├── Network Master Identity (NMI) - Network creator/controller
       ├── Backend Infrastructure Nodes - Trusted servers (auth, user rights, etc.)
       └── Regular Peer Nodes - Network participants
```

### Components

#### 1. **CXNetwork**
Individual logical networks within the global CXNET.

**Fields:**
- `networkDictionary` - Network metadata (ID, NMI, creation time, chain IDs)
- `configuration` - Network settings (NMI public key, backendSet, active status)
- `c1, c2, c3` - Three blockchain chains (Admin, Resources, Events)
- `networkPermissions` - Permission-based access control container

**Key Characteristics:**
- Created by a Network Master Identity (NMI)
- Contains 3 independent blockchains
- Has infrastructure nodes in `backendSet`

#### 2. **Network Master Identity (NMI)**
The identity that creates and controls a CXNetwork.

**Properties:**
- Full permissions (weight 100) for all network operations
- Public key stored in:
  - `Configuration.nmiPub`
  - `NetworkDictionary.nmi`
- Automatically added to `backendSet` during network creation

**Responsibilities:**
- Create and configure the network
- Manage permissions (add/edit entries)
- Manage backend infrastructure nodes
- Sign network configuration

#### 3. **Backend Infrastructure Nodes (`backendSet`)**
Trusted infrastructure servers for specific network services.

**Purpose:**
- Provide network infrastructure (authentication, user rights, etc.)
- Get **priority routing** for CXN-scoped messages
- Examples: AnvilDevelopment authentication servers, user rights platforms

**Important:** Regular peers are NOT in backendSet - only infrastructure.

#### 4. **Peer Nodes**
Regular participants in networks.

**Storage:**
- `PeerDirectory.hv` - High-value peer cache (active peers)
- `PeerDirectory.lan` - LAN peers
- `PeerDirectory.peerCache` - General peer cache
- `PeerDirectory.seen` - Last seen timestamps

**Privacy Design:**
No association data between nodes and networks is stored. Peers filter messages they receive to determine relevance.

---

## Encryption & Security

### Three-Layer Encryption System

#### **Layer 1: NetworkContainer (Transport Security)**
```
NetworkContainer {
    byte[] e;           // Encrypted NetworkEvent
    String iD;          // Transmitter ID (current hop)
    TransmitPref tP;    // Routing preferences
    String se;          // Serialization method
    boolean s;          // E2E mode (not implemented)
    CXPath p;           // Routing path
}
```

- **Signed by:** Current transmitter (hop-by-hop)
- **Purpose:** Transport-level authentication
- **Verification:** `encryptionProvider.verifyAndStrip(stream, output, transmitterID)`

#### **Layer 2: NetworkEvent (Message Security)**
```
NetworkEvent {
    String eT;          // Event type
    byte[] d;           // Event data (payload)
    CXPath p;           // Routing path
    String iD;          // Event ID
}
```

- **Signed by:** Event sender (or E2E if applicable)
- **Purpose:** Message-level integrity
- **Contains:** Encrypted event data

#### **Layer 3: NetworkEvent.d (Payload Security)**
- **Signed by:** Application-specific, E2E for ConnectX core functions
- **Purpose:** Payload-level security
- **Contains:** Actual event data

### Key Management

**Own Keys:**
- Secret key: `{cxRoot}/key.cx`
- Public key: `ConnectX.self.publicKey`

**Network Master Key:**
- `{cxRoot}/cx.asc` - CXNET/network NMI public key

**Peer Keys:**
- Cached in `PeerDirectory`
- Cached in `encryptionProvider` certificate cache

### Two-Layer Signature System

ConnectX uses a two-layer signature system to distinguish between the **original sender** and **current transmitter** in multi-hop P2P networks.

#### Layer 1: NetworkEvent Signature (Inner Layer)
```
NetworkEvent → Signed by ORIGINAL SENDER → Preserved through relay chain
```
- **Signed by:** Original message sender
- **Purpose:** Proves message authorship (who created the event)
- **Preservation:** Signature MUST NOT change during relay
- **Implementation:** `signedEventBytes` in InputBundle preserves original signature

#### Layer 2: NetworkContainer Signature (Outer Layer)
```
NetworkContainer → Signed by CURRENT TRANSMITTER → Changes each hop
```
- **Signed by:** Current transmitter (node forwarding the message)
- **Purpose:** Proves transport legitimacy (who sent this hop)
- **Field:** `nc.iD` - **CRITICAL:** Must be set before signing NetworkContainer
- **Changes:** Each relay creates new NetworkContainer with their own iD

#### Relay Process (OutConnectionController.transmitEvent)

**CRITICAL IMPLEMENTATION DETAIL:**
```java
// Step 1: ALWAYS set transmitter ID first (even for relays)
nc.iD = connectXAPI.getOwnID();

// Step 2: Handle event bytes
if (out.prev != null) {
    // Relaying: Use preserved signed event bytes (original sender's signature)
    cryptEvent = out.prev;
} else {
    // New message: Sign the NetworkEvent with our key
    cryptEvent = signObject(out.ne, NetworkEvent.class);
}

// Step 3: Embed signed event in container
nc.e = cryptEvent;

// Step 4: Sign the NetworkContainer with our key (outer layer)
cryptNetworkContainer = signObject(nc, NetworkContainer.class);
```

**Why This Matters:**
- Without `nc.iD`, recipients cannot verify the NetworkContainer signature
- Previous bug: Relays didn't set `nc.iD` when using `out.prev`, causing NullPointerException
- Fix: Always set `nc.iD` before signing, regardless of relay vs. new message

#### Verification Process (NodeMesh.processNetworkInput)

```java
// Step 1: Decrypt and deserialize NetworkContainer
nc = deserialize(networkContainer);

// Step 2: CRITICAL - Verify nc.iD exists
if (nc.iD == null) {
    throw DecryptionFailureException(); // Container missing transmitter ID
}

// Step 3: Verify NetworkContainer signature using transmitter's ID
verifyAndStrip(nc.e, output, nc.iD);

// Step 4: Deserialize and handle NetworkEvent
ne = deserialize(output);
```

**Security Benefits:**
1. **Original Sender Authentication:** NetworkEvent signature proves authorship
2. **Hop-by-Hop Verification:** NetworkContainer signature proves each relay is legitimate
3. **Relay Chain Trust:** Can track message path through transmitter IDs
4. **Anti-Tampering:** Any modification breaks signature chain

### Duplicate Detection System

In P2P mesh networks, the same event can arrive via multiple relay paths. ConnectX implements duplicate detection to ensure each unique event is processed exactly once.

#### Event Unique ID Generation

**Where:** `OutConnectionController.transmitEvent()` (line 29-32)

```java
// Generate UUID for new messages (not relays)
if (out.prev == null && (out.ne.iD == null || out.ne.iD.isEmpty())) {
    out.ne.iD = java.util.UUID.randomUUID().toString();
}
```

- **Field:** `NetworkEvent.iD`
- **Type:** UUID string
- **Status:** **MANDATORY** (enforced - events without IDs are rejected)
- **Generated:** Automatically when creating new events (not when relaying)
- **Preserved:** Through entire relay chain (part of signed NetworkEvent)
- **Security:** Required for duplicate detection and replay attack prevention

#### Duplicate Checking

**Where:** `NodeMesh.processNetworkInput()` (line 168-201)

```java
// Check transmissionIDMap for previously seen event IDs
if (transmissionIDMap.containsKey(ne.iD)) {
    return; // Drop duplicate - already processed
}

// First time seeing this event - record it
transmissionIDMap.put(ne.iD, transmitterList);
```

**Data Structure:**
```java
ConcurrentHashMap<String, ArrayList<String>> transmissionIDMap
                    ▲                ▲
                    │                └── List of transmitters who sent this event
                    └── Event ID (ne.iD)
```

**Behavior:**
1. **First receipt:** Event ID recorded in `transmissionIDMap`, event processed and relayed
2. **Subsequent receipts:** Event dropped silently (no processing, no relay)
3. **Tracking:** Multiple transmitters for same event ID tracked for debugging/analysis

**Memory Management:**
- **Limit:** 10,000 event IDs maximum
- **Cleanup:** When limit reached, remove oldest 1,000 entries (FIFO)
- **Purpose:** Prevent unbounded memory growth in long-running peers

#### Example: Mesh Network Duplicate Prevention

```
Scenario: Peer1 sends message to 4-peer network

Time 0: Peer1 → broadcasts to Peer2, Peer3, Peer4
Time 1: Peer2 receives (ID: abc123)
        - Not in transmissionIDMap
        - Process & relay to Peer3, Peer4
Time 2: Peer3 receives from Peer1 (ID: abc123)
        - Not in transmissionIDMap
        - Process & relay to Peer2, Peer4
Time 3: Peer3 receives from Peer2 (ID: abc123)
        - Already in transmissionIDMap!
        - DROP (no processing, no relay)
Time 4: Peer4 receives from multiple sources (ID: abc123)
        - First receipt: Process & relay
        - Subsequent: DROP all duplicates
```

**Result:** Each peer processes the message exactly once, preventing relay storms.

#### Security Enforcement

**Event IDs are MANDATORY** (NodeMesh.processNetworkInput:168-173):

```java
// Reject events without IDs
if (ne.iD == null || ne.iD.isEmpty()) {
    Analytics.addData(AnalyticData.Tear, "NetworkEvent missing required event ID");
    return; // Reject - no processing
}
```

**Rationale:**
- **Replay Attack Prevention:** Without IDs, attackers could replay old messages
- **Duplicate Detection:** IDs are essential for tracking seen events in mesh networks
- **Audit Trail:** IDs enable tracking and debugging of message flows
- **Security Best Practice:** No exceptions for legacy compatibility

**Impact:** All events transmitted in ConnectX v3.0 MUST have unique IDs

---

## Routing & Transmission

### CXPath Scopes

Messages are routed based on `CXPath.scope`:

| Scope | Description | Routing Behavior |
|-------|-------------|------------------|
| **CXNET** | Global broadcast | All peers in `PeerDirectory.hv`<br>**Authorization:** Only CXNET backendSet or NMI |
| **CXN** | Network routing | Backend nodes (priority), then all peers<br>**Filter:** Receivers filter by network membership |
| **CXS** | Single peer | Direct to `CXPath.cxID`<br>No relay |
| **CX** | Blockchain only | Record to blockchain<br>No transmission |

### TransmitPref Routing Modes

`NetworkContainer.tP` controls relay behavior:

#### **directOnly = true**
```
Behavior: No relaying after initial delivery
Use Case: Point-to-point when target is reachable
Relay:    None
```

#### **peerProxy = true**
```
Behavior: Record to blockchain + distribute to all peers
Use Case: Guaranteed eventual delivery, uncertain target availability
Relay:    Broadcast to all in PeerDirectory.hv
```

#### **peerBroad = true** (default)
```
Behavior: Global cross-network transmission
Use Case: Network-wide announcements, cross-network messages
Relay:    Broadcast to all peers across all networks
```

#### **All false** (Standard Mode)
```
Behavior: CXN scope with priority routing
Use Case: Normal network communication with infrastructure priority
Relay:    Backend nodes first, then all peers
```

### Routing Flow

#### Initial Transmission (OutConnectionController)
```
1. Sign NetworkEvent with sender's key
2. Set nc.iD = own ID (transmitter)
3. Initialize TransmitPref if null
4. Sign NetworkContainer
5. Route based on CXPath.scope:
   - CXNET → All peers in hv
   - CXN   → Backend nodes in backendSet
   - CXS   → Single peer by cxID
   - CX    → No transmission (local)
```

#### Relay Logic (NodeMesh.fireEvent)
```
1. Check if we transmitted → Don't relay (loop prevention)
2. Check TransmitPref:
   - directOnly → No relay
   - peerProxy  → Record to blockchain + relay to all
   - peerBroad  → Broadcast to all peers
   - Standard   → CXN priority routing
3. For CXN scope:
   - Send to backendSet (priority)
   - Send to all other peers
4. Preserve TransmitPref in relayed messages
```

### Security Validations

#### CXNET Authorization (processNetworkInput:120-143)
```java
// Only CXNET backendSet or NMI can send CXNET messages
if (scope == "CXNET") {
    authorized = cxnet.backendSet.contains(transmitterID) ||
                 transmitterID.equals(cxnet.nmiPub);
    if (!authorized) {
        drop_message();
    }
}
```

#### Relay Loop Prevention
```java
// Don't relay our own transmissions
if (nc.iD.equals(ownID)) {
    return; // We transmitted this
}

// Don't relay back to transmitter
if (!targetID.equals(transmitterID)) {
    relay();
}
```

---

## Blockchain Structure

### Three-Chain Architecture

Each CXNetwork maintains 3 independent blockchains:

| Chain | Purpose | Chain ID | Use Cases |
|-------|---------|----------|-----------|
| **c1** | Administrative | 1 | Network config, permissions, backend management |
| **c2** | Resources | 2 | Resource registration, availability, updates |
| **c3** | Events | 3 | Network events, messages, audit trail |

### Components

#### NetworkRecord
```java
class NetworkRecord {
    String networkID;           // Network identifier
    Long chainID;               // 1, 2, or 3
    NetworkBlock currentBlock;  // Current block
}
```

#### NetworkBlock
```java
class NetworkBlock {
    Long blockID;
    Long previousBlockID;
    String previousHash;
    Long timestamp;
    ConcurrentHashMap<String, NetworkEvent> networkEvents;
}
```

### Recording Events

```java
// Automatic recording based on scope
if (scope.equalsIgnoreCase("CX")) {
    ConnectX.recordEvent(networkEvent);
}

// Explicit recording (peerProxy mode)
if (tP.peerProxy) {
    ConnectX.recordEvent(networkEvent); // If permissions allow
}
```

---

## Event System

### Event Flow

```
┌─────────────┐
│ Create Event│
└──────┬──────┘
       │
       ▼
┌─────────────┐
│outputQueue  │ ◄── User/Plugin creates OutputBundle
└──────┬──────┘
       │
       ▼
┌─────────────────────┐
│ OutputProcessor     │ ◄── Thread polls queue
└──────┬──────────────┘
       │
       ▼
┌──────────────────────────┐
│ OutConnectionController  │ ◄── Sign & Encrypt
└──────┬───────────────────┘
       │
       ▼
┌─────────────┐
│  Transmit   │ ◄── Socket write based on scope
└──────┬──────┘
       │
       ▼
┌─────────────────────┐
│ Receive (Socket)    │ ◄── Remote peer
└──────┬──────────────┘
       │
       ▼
┌──────────────────────────┐
│ processNetworkInput      │ ◄── Verify & Decrypt
└──────┬───────────────────┘
       │
       ▼
┌─────────────┐
│ eventQueue  │ ◄── InputBundle queued
└──────┬──────┘
       │
       ▼
┌─────────────────┐
│ EventProcessor  │ ◄── Thread polls queue
└──────┬──────────┘
       │
       ▼
┌─────────────────┐
│ processEvent    │ ◄── Infrastructure handling
└──────┬──────────┘
       │
       ▼
┌─────────────────┐
│  fireEvent      │ ◄── Plugin dispatch + Relay logic
└─────────────────┘
```

### Event Types

```java
enum EventType {
    MESSAGE,      // Text messages
    NewNode,      // Peer discovery/announcement
    PeerFinding,  // Peer discovery requests
    // Extensible via plugins
}
```

### Plugin System

Events are dispatched to plugins:
```java
ConnectX.sendPluginEvent(networkEvent, eventType);
```

Plugins can handle custom event types unknown to core.

---

## Permissions & Access Control

### Permission System

Based on weighted entries:
```
Map<String, Map<String, Entry>> permissionSet
    ▲        ▲           ▲
    │        │           └── Permission entry (granted, weight)
    │        └── Permission name
    └── Peer cxID
```

### Permission Weights

| Role | Weight | Description |
|------|--------|-------------|
| NMI | 100 | Full control, highest priority |
| Default joining nodes | 10 | Limited permissions |

Higher weight wins on conflicts.

### Core Permissions

```java
enum Permission {
    AddAccount,             // Add accounts to network
    NetworkUpload,          // Upload to network
    UploadGlobalResource,   // Upload global resources
    Record,                 // Record to blockchain (chain-specific)
}
```

### Chain-Specific Permissions

Format: `Record-{chainID}`
- `Record-1` - Admin chain recording
- `Record-2` - Resources chain recording
- `Record-3` - Events chain recording

### Default Permissions

**NMI (weight 100):**
- All permissions granted
- Can add/edit other permissions

**Joining nodes (weight 10):**
- `AddAccount` - Can add themselves
- `Record-3` - Can record to Events chain

---

## Implementation Notes

### Threading Model

| Thread | Purpose | Queue |
|--------|---------|-------|
| IOThread (×N) | Process I/O jobs | Job queue |
| SocketWatcher | Accept connections | N/A |
| EventProcessor | Process incoming events | eventQueue |
| OutputProcessor | Process outgoing events | outputQueue |

### Thread Safety

- Queues use `synchronized` blocks
- `PeerDirectory` uses `ConcurrentHashMap`
- `NetworkBlock` uses `ConcurrentHashMap` for events

### Privacy Design

**No node-network association data is stored.**

Rationale:
- Privacy/security consideration
- Space/resource optimization
- Peer-based filtering instead of sender-based

Result:
- CXN messages broadcast to all peers
- Receivers filter by network membership
- No central registry of who's in what network

### Network Isolation

Each `ConnectX` instance represents one peer:
- Own working directory (`cxRoot`)
- Own keys and configuration
- Own event/output queues
- Shared global `PeerDirectory`

---

## Protocol Summary

**ConnectX Features:**
- ✅ Decentralized P2P mesh networking
- ✅ Multi-layer PGP encryption (3 layers)
- ✅ Two-layer signature system (sender + transmitter)
- ✅ Duplicate detection (UUID-based event tracking)
- ✅ Blockchain persistence (3 chains per network)
- ✅ Flexible routing (CXNET, CXN, CXS, CX)
- ✅ TransmitPref routing control (directOnly, peerProxy, peerBroad)
- ✅ Priority infrastructure nodes (backendSet)
- ✅ Weighted permission system
- ✅ Plugin-extensible events
- ✅ CXNET authorization validation
- ✅ Relay loop prevention
- ✅ Privacy-preserving (no node-network tracking)
- ✅ Cross-network communication

---

## Example: Message Flow

### Scenario: Peer1 sends MESSAGE to TESTNET

```
1. Peer1 creates NetworkEvent (MESSAGE, "Hello")
2. Set CXPath: scope=CXN, network=TESTNET
3. Add to outputQueue
4. OutputProcessor picks up
5. OutConnectionController:
   - Signs event with Peer1's key
   - Sets nc.iD = "PEER1"
   - Signs NetworkContainer
   - Sends to TESTNET backendSet nodes
6. Peer2 (backend node) receives:
   - Verifies signatures
   - Adds to eventQueue
7. EventProcessor processes
8. fireEvent():
   - Dispatches to plugins
   - Checks: nc.iD != own ID, so relay
   - Relays to all peers (backend first, then others)
9. Peer3 (regular peer) receives:
   - Verifies signatures
   - Processes event
   - Checks: part of TESTNET? Keep : Drop
   - fireEvent(): nc.iD != own ID, relay again
   - But: Peer3 already in sentTo, skip
```

Result: All TESTNET members receive message exactly once.

---

*ConnectX Protocol v3.0 - Decentralized, Secure, Private*