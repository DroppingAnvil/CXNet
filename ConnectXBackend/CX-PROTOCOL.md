# ConnectX (CX) Protocol Documentation

**Version:** 3.0
**Last Updated:** 2025-01-24 (Security Features & Bootstrap)

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

**NMI Management:**

Individual networks can manage their NMIs through the following event types:
- `UPDATE_NMI` - Update existing NMI credentials (requires existing NMI signature)
- `ADD_NMI` - Add additional NMI for redundancy (requires existing NMI signature)
- `DELETE_NMI` - Remove an NMI (requires existing NMI signature)

All NMI management operations require proper permission verification using the existing permission system.

**CXNET NMI Immutability:**
The CXNET NMI is immutable and should never change under normal circumstances. Exception conditions for CXNET NMI updates will be defined in future protocol updates. This ensures the stability and security of the global network infrastructure.

**Network Seed Distribution:**

Each network's seed data is stored in Chain 1 (c1 - Admin chain) and must be signed by that network's NMI. This ensures:
- **Authenticity:** Only the network's NMI can publish official seed data
- **Peer Discovery:** New nodes joining a network can find peers through c1 blockchain
- **Integrity:** Seed data is cryptographically verified through NMI signature

When joining a new network, nodes MUST retrieve the network seed from c1 to discover initial peers.

**CXNET Official Seed Policy:**

Networks wishing to be included in the CXNET official seed distribution must meet strict policies to ensure the health and stability of the global network. These policies include (but are not limited to):
- **Security Requirements:** Strong NMI key management and security practices
- **Performance Standards:** Minimum uptime and response time requirements
- **Compliance:** Adherence to CXNET protocol specifications
- **Application Review:** Networks are evaluated based on their intended use case

Additional information on security requirements, performance benchmarks, and application procedures will be available at:
**https://AnvilDevelopment.US/ConnectX**

**Default Permissions:**

CXNET enforces restrictive default permissions to maintain system health:
- **CXNET:** Nodes joining CXNET receive only `AddAccount` permission by default (NO blockchain recording)
- **Other Networks:** Nodes receive `AddAccount` + `Record-c3` (events chain) permissions

This ensures that CXNET blockchain is not spammed with arbitrary events, while individual networks maintain flexibility for their use cases.

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

## Event Construction Patterns

### CRITICAL: buildEvent() vs Manual Construction

**⚠️ WARNING: Most events MUST use `buildEvent()` API. Manual construction is ONLY for special cases.**

ConnectX provides two ways to create events:

1. **`buildEvent()` API (REQUIRED FOR 99% OF CASES)**
2. **Manual OutputBundle construction (ONLY for bootstrap/discovery)**

### When to Use buildEvent() API

**USE buildEvent() FOR:**
- All normal network communication
- Peer-to-peer messages (CXS scope)
- Network broadcasts (CXN scope)
- Global broadcasts (CXNET scope)
- Blockchain-recorded events (CX scope)
- Any event where you have the peer's ID

**Why buildEvent() is Required:**
```java
// buildEvent() automatically sets up:
// 1. Permission/CXPath (event.p) - CRITICAL for routing
// 2. TransmitPref (routing preferences)
// 3. Event ID (UUID for duplicate detection)
// 4. Scope-based routing logic
// 5. Network context
```

**Example:**
```java
// CORRECT: Use buildEvent() for normal events
connectX.buildEvent(EventType.MESSAGE, "Hello".getBytes())
    .scope("CXN")              // Network broadcast
    .toNetwork("TESTNET")      // Target network
    .queue();                  // Add to output queue

// CORRECT: Use buildEvent() for single-peer events
connectX.buildEvent(EventType.CHAIN_STATUS_REQUEST, data)
    .toPeer(peerNode)          // Target specific peer
    .queue();
```

### When to Use Manual Construction

**USE MANUAL CONSTRUCTION ONLY FOR:**
1. **Bootstrap events (NEWNODE)** - Introducing to unknown peer (EPOCH) before network membership
2. **LAN discovery (CXHELLO)** - Discovering peers without knowing their peer ID
3. **Direct transmission fallback** - When you have address but NO peer ID

**⚠️ Manual construction bypasses CX protocol safeguards. Use with extreme caution.**

### Manual Construction Pattern (NEWNODE Example)

**Used in:** Bootstrap to EPOCH (ConnectX.java:984-1023)

```java
// Step 1: Manually create NetworkEvent
NetworkEvent newNodeEvent = new NetworkEvent(EventType.NewNode, new byte[0]);
newNodeEvent.eT = EventType.NewNode.name();
newNodeEvent.iD = java.util.UUID.randomUUID().toString();
newNodeEvent.d = selfJson.getBytes("UTF-8");

// Step 2: Manually set Permission/CXPath
CXPath epochPath = new CXPath();
epochPath.cxID = EPOCH_UUID;           // Target EPOCH specifically
epochPath.scope = "CXS";                // Single peer scope
epochPath.bridge = "cxHTTP1";           // HTTP bridge protocol
epochPath.bridgeArg = "https://...";    // HTTP bridge URL
newNodeEvent.p = epochPath;              // CRITICAL: Set permission/path

// Step 3: Create target Node
Node epochNode = new Node();
epochNode.cxID = EPOCH_UUID;
epochNode.addr = "cxHTTP1:https://...";

// Step 4: Manually create NetworkContainer
NetworkContainer newNodeContainer = new NetworkContainer();
newNodeContainer.se = "cxJSON1";
newNodeContainer.s = false;              // Not E2E encrypted
newNodeContainer.iD = self.cxID;         // Our ID

// Step 5: Create OutputBundle directly (bypassing buildEvent)
OutputBundle newNodeBundle = new OutputBundle(
    newNodeEvent,      // Event
    epochNode,         // Target node
    null,              // Recipient public key (null for now)
    null,              // Signature (will be signed later)
    newNodeContainer   // Container
);

// Step 6: Queue directly
queueEvent(newNodeBundle);
```

**Why This Works for NEWNODE:**
- We know EPOCH's UUID (hardcoded)
- We know EPOCH's address (bootstrap address)
- We manually set CXPath with bridge info
- OutConnectionController uses CXS routing (lines 76-251)

### Manual Construction Pattern (CXHELLO Example)

**Used in:** LAN Discovery (LANScanner.java:204-240)

```java
// Step 1: Create payload
Map<String, Object> payload = new HashMap<>();
payload.put("peerID", connectX.getOwnID());
payload.put("port", primaryPort);
payload.put("localIP", getLocalIP());
String payloadJson = ConnectX.serialize("cxJSON1", payload);

// Step 2: Manually create NetworkEvent
NetworkEvent helloEvent = new NetworkEvent(EventType.CXHELLO, payloadJson.getBytes("UTF-8"));
helloEvent.eT = EventType.CXHELLO.name();
helloEvent.iD = java.util.UUID.randomUUID().toString();
// DON'T set event.p - leave null for direct transmission fallback

// Step 3: Create target node with address only (NO peer ID)
Node targetNode = new Node();
targetNode.addr = targetIP + ":" + targetPort;  // e.g., "192.168.1.100:49152"
// targetNode.cxID = null (unknown - that's the point of discovery!)

// Step 4: Manually create NetworkContainer
NetworkContainer nc = new NetworkContainer();
nc.se = "cxJSON1";
nc.s = false;  // Not signed (unknown peer, no key exchange yet)

// Step 5: Create OutputBundle directly
OutputBundle bundle = new OutputBundle(helloEvent, targetNode, null, null, nc);

// Step 6: Queue directly
connectX.queueEvent(bundle);
```

**Why This Works for CXHELLO:**
- We DON'T know the peer's ID (discovering them!)
- We DO know their address (from LAN scan)
- We DON'T set event.p (Permission/CXPath) - leave null
- This triggers direct transmission fallback in OutConnectionController (line 252-266)

### Routing Logic in OutConnectionController

**Location:** `OutConnectionController.transmitEvent()` (lines 53-274)

The routing logic has THREE paths based on `event.p.scope`:

#### Path 1: CXNET Scope (Global Broadcast)
**Lines:** 59-74
**Condition:** `event.p != null && event.p.scope.equalsIgnoreCase("CXNET")`
**Behavior:** Broadcast to ALL peers in `PeerDirectory.hv`
**Authorization:** Only CXNET backendSet or NMI can send
```java
if (out.ne.p != null && out.ne.p.scope != null && out.ne.p.scope.equalsIgnoreCase("CXNET")) {
    // Broadcast to all high-value peers
    for (Node n : PeerDirectory.hv.values()) {
        // Try socket transmission to each peer
    }
}
```

#### Path 2: CXS/CXN Scope (Normal Routing)
**Lines:** 75-251
**Condition:** `event.p != null && event.p.scope != null`
**Behavior:** Scope-based routing

**CXS (Single Peer):**
- Lookup peer by `event.p.cxID`
- Try ALL available routes (multi-path):
  1. HTTP bridge from `node.addr`
  2. CXPath bridge from `event.p.bridge`
  3. LAN direct from `DataContainer.getLocalPeerAddress()`
- Log: `[Multi-Path] Sent to abcd1234 via: cxHTTP1+LAN-Direct`

**CXN (Network Broadcast):**
- Send to backend nodes first (priority)
- Then send to all other peers
- Each peer tries multi-path routing

```java
else if (out.ne.p != null && out.ne.p.scope != null) {
    if (out.ne.p.scope.equalsIgnoreCase("CXS")) {
        // Single peer - lookup by cxID
        Node n = PeerDirectory.lookup(out.ne.p.cxID, true, true);

        // Try ALL routes for redundancy:
        // 1. HTTP bridge
        // 2. P2P direct
        // 3. LAN direct
    }
}
```

#### Path 3: Direct Transmission Fallback
**Lines:** 252-266
**Condition:** `out.n != null && out.n.addr != null && !out.n.addr.isEmpty()`
**Behavior:** Direct socket transmission to address (NO peer ID needed)
**Used For:** CXHELLO discovery, bootstrap to unknown peers

```java
} else if (out.n != null && out.n.addr != null && !out.n.addr.isEmpty()) {
    // Special case: Direct transmission to address (for CXHELLO discovery)
    // Used when we have a target address but no peer ID yet
    System.out.println("[OutController] Attempting direct transmission to " + out.n.addr);
    try {
        String[] addr = out.n.addr.split(":");
        Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
        s.getOutputStream().write(cryptNetworkContainer);
        s.getOutputStream().flush();
        s.close();
        System.out.println("[OutController] Direct transmission SUCCESS to " + out.n.addr);
    } catch (Exception e) {
        System.out.println("[OutController] Direct transmission FAILED to " + out.n.addr + ": " + e.getMessage());
    }
}
```

**Critical Requirements for Direct Transmission:**
1. `out.n` must be set (target Node object)
2. `out.n.addr` must be set (IP:port string)
3. `event.p` should be NULL (or at least not have scope set)
4. This path is checked AFTER CXNET and CXS/CXN paths

### Common Mistakes

#### ❌ WRONG: Using buildEvent() for LAN Discovery
```java
// This FAILS because .toPeer() sets scope="CXS" which requires cxID
connectX.buildEvent(EventType.CXHELLO, data)
    .toPeer(targetNode)  // ERROR: Sets CXS scope, but no cxID!
    .queue();
// Result: CXS routing tries to lookup null cxID and fails
```

#### ❌ WRONG: Manual construction for normal events
```java
// This BYPASSES protocol safeguards!
NetworkEvent badEvent = new NetworkEvent(EventType.MESSAGE, data);
// Missing: Permission/CXPath, TransmitPref, proper routing setup
// Result: Event may not be routed correctly or may violate protocol
```

#### ❌ WRONG: Using empty constructor instead of proper constructor
```java
// WRONG: Using default constructor
NetworkEvent event = new NetworkEvent();  // Doesn't set eT or d properly
event.eT = EventType.CXHELLO.name();

// CORRECT: Use proper constructor
NetworkEvent event = new NetworkEvent(EventType.CXHELLO, payloadData);
event.eT = EventType.CXHELLO.name();  // Redundant but ensures consistency
```

#### ✅ CORRECT: Using buildEvent() for normal peer communication
```java
// Correct: buildEvent() handles everything
connectX.buildEvent(EventType.MESSAGE, "Hello".getBytes())
    .scope("CXS")
    .toPeer(peerNode)  // Has cxID - CXS routing works
    .queue();
```

### Debugging Event Construction Issues

**Symptoms of Wrong Pattern:**
1. Events queued but never transmitted (`queue size grows, no OUT-DEBUG logs`)
2. `[OutController] WARNING: No routing for EVENTTYPE` logs
3. Events arrive with 0 bytes (`SocketWatcher buffered 0 bytes`)
4. Decryption failures on receiving end

**How to Diagnose:**

**Check 1: Is event reaching OutputProcessor?**
```
Look for: [OUT-DEBUG] Type=EVENTTYPE, Node=..., Perm=...
If missing: Event not being polled from queue (check event construction)
```

**Check 2: Is routing info present?**
```
Look for: [OutController] WARNING: No routing for EVENTTYPE
If present: event.p is null or invalid, AND out.n is null/invalid
```

**Check 3: Which routing path is being used?**
```
[Multi-Path] ... → CXS routing (single peer)
[Multi-Path CXN] ... → CXN routing (network broadcast)
[OutController] Attempting direct transmission → Direct fallback
```

**Check 4: Is event.p properly set?**
```java
// Add logging before queueEvent()
System.out.println("Event.p: " + (event.p != null ? event.p.scope : "NULL"));
System.out.println("Event.p.cxID: " + (event.p != null ? event.p.cxID : "NULL"));
System.out.println("Node.addr: " + (node != null ? node.addr : "NULL"));
```

### Implementation Checklist

**Before implementing custom event:**

1. ☐ Can I use `buildEvent()` API? (99% of cases: YES)
2. ☐ Do I know the target peer's ID? (YES → use buildEvent())
3. ☐ Do I need direct transmission without peer ID? (NO → use buildEvent())
4. ☐ Is this a bootstrap/discovery event? (NO → use buildEvent())

**If manual construction is needed:**

1. ☐ Use proper NetworkEvent constructor: `new NetworkEvent(EventType, byte[])`
2. ☐ Set `event.eT` explicitly
3. ☐ Set `event.iD = UUID.randomUUID().toString()`
4. ☐ Set `event.p` (Permission/CXPath) if using CXS/CXN routing
5. ☐ Leave `event.p = null` if using direct transmission fallback
6. ☐ Create NetworkContainer with `se = "cxJSON1"`, `s = false`
7. ☐ Set `nc.iD` (will be set by OutConnectionController, but good practice)
8. ☐ Create OutputBundle with all components
9. ☐ Queue with `connectX.queueEvent(bundle)`
10. ☐ Test thoroughly with logging enabled

### Summary

**Golden Rule: Use `buildEvent()` unless you absolutely cannot.**

**Only use manual construction when:**
- Bootstrapping to unknown peer (NEWNODE)
- Discovering peers without ID (CXHELLO)
- Direct transmission to address without peer ID

**Why buildEvent() is critical:**
- Sets up CXPath/Permission correctly
- Initializes TransmitPref
- Generates event ID (duplicate detection)
- Ensures protocol compliance
- Handles scope-based routing automatically

**Manual construction risks:**
- Easy to misconfigure routing
- Easy to forget critical fields
- Bypasses protocol safeguards
- Harder to debug
- May break on protocol updates

**When in doubt: Use buildEvent().**

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

### Blockchain Persistence Layer

ConnectX implements a scalable block-per-file persistence architecture for blockchain data. This enables efficient storage and retrieval of blockchain history while supporting lazy loading for memory optimization.

#### Architecture Overview

**Design Philosophy:**
- **Lightweight chain metadata** - Only pointers and configuration (no block data)
- **Block-per-file storage** - Each block stored as individual JSON file
- **Lazy loading** - Blocks loaded on-demand from disk
- **Thread-safe** - Per-chain locks prevent concurrent modification
- **Scalable** - Supports up to 100 chains per network

#### File Structure

```
{cxRoot}/
  └── blockchain/
      └── {networkID}/
          ├── chain-1.json           (c1 metadata - lightweight)
          ├── chain-2.json           (c2 metadata - lightweight)
          ├── chain-3.json           (c3 metadata - lightweight)
          ├── ...
          ├── chain-100.json         (up to 100 chains)
          └── blocks/
              ├── chain-1/
              │   ├── block-0.json   (individual block files)
              │   ├── block-1.json
              │   └── block-2.json
              ├── chain-2/
              │   └── block-0.json
              └── chain-3/
                  ├── block-0.json
                  └── block-1.json
```

#### Components

##### 1. BlockchainPersistence Class
**Location:** `dev.droppinganvil.v3.network.BlockchainPersistence`

**Responsibilities:**
- Save/load individual blocks
- Save/load chain metadata
- Manage per-chain locks for thread safety
- Validate chain IDs (1-100)
- Delete network blockchain data

**Key Methods:**
```java
// Save operations
void saveBlock(String networkID, Long chainID, NetworkBlock block)
void saveChainMetadata(NetworkRecord chain, String networkID)
void saveAllBlocks(NetworkRecord chain, String networkID)

// Load operations
NetworkBlock loadBlock(String networkID, Long chainID, Long blockID)
NetworkRecord loadChain(String networkID, Long chainID, boolean loadAllBlocks)

// Utility operations
void deleteNetwork(String networkID)
boolean exists(String networkID)
boolean chainExists(String networkID, Long chainID)
```

##### 2. ChainMetadata Class
**Location:** `BlockchainPersistence.ChainMetadata`

**Purpose:** Lightweight metadata for efficient chain serialization (no block data)

```java
class ChainMetadata {
    String networkID;        // Network identifier
    Long chainID;            // 1-100
    Integer blockLength;     // Events per block (default: 100)
    boolean lock;            // Chain lock status
    Long currentBlockID;     // Pointer to current block (NOT the block itself)
    int blockCount;          // Total number of blocks on disk
}
```

**Size:** ~167 bytes per chain (minimal overhead)

##### 3. NetworkBlock Files
**Format:** JSON serialized NetworkBlock objects

```json
["dev.droppinganvil.v3.edge.NetworkBlock", {
  "block": 0,
  "networkEvents": ["java.util.concurrent.ConcurrentHashMap", {...}]
}]
```

**Size:** Variable (116 bytes for empty genesis block, grows with events)

#### Persistence Triggers

Blockchain data is automatically persisted at key lifecycle points:

##### 1. Network Creation (ConnectX.createNetwork)
```java
// Save genesis blocks for all three chains
blockchainPersistence.saveBlock(networkID, 1L, network.c1.current);
blockchainPersistence.saveBlock(networkID, 2L, network.c2.current);
blockchainPersistence.saveBlock(networkID, 3L, network.c3.current);

// Save chain metadata
blockchainPersistence.saveChainMetadata(network.c1, networkID);
blockchainPersistence.saveChainMetadata(network.c2, networkID);
blockchainPersistence.saveChainMetadata(network.c3, networkID);
```

##### 2. Block Rotation (ConnectX.recordEvent)
```java
// When current block reaches blockLength (default: 100 events)
if (currentBlock.networkEvents.size() >= targetChain.blockLength) {
    // Save completed block before creating new one
    blockchainPersistence.saveBlock(networkID, chainID, currentBlock);
    blockchainPersistence.saveChainMetadata(targetChain, networkID);

    // Create new block
    Long newBlockID = currentBlock.block + 1;
    NetworkBlock newBlock = new NetworkBlock(newBlockID);
    targetChain.current = newBlock;
}
```

##### 3. Network Import (ConnectX.importNetwork)
```java
// Try to load persisted blockchain data from disk
if (blockchainPersistence.exists(networkID)) {
    // Load chains (lazy loading - only current blocks initially)
    NetworkRecord c1 = blockchainPersistence.loadChain(networkID, 1L, false);
    NetworkRecord c2 = blockchainPersistence.loadChain(networkID, 2L, false);
    NetworkRecord c3 = blockchainPersistence.loadChain(networkID, 3L, false);

    // Apply loaded chains to network
    if (c1 != null) network.c1 = c1;
    if (c2 != null) network.c2 = c2;
    if (c3 != null) network.c3 = c3;
}
```

#### Lazy Loading Strategy

**Problem:** Loading all blocks into memory would be memory-intensive for large blockchains.

**Solution:** Only load current block on startup; load historical blocks on-demand.

```java
// Load chain with lazy loading (default)
NetworkRecord chain = blockchainPersistence.loadChain(networkID, chainID, false);
// Result: Only current block loaded into memory

// Load all blocks (when needed)
NetworkRecord chain = blockchainPersistence.loadChain(networkID, chainID, true);
// Result: All blocks loaded into memory
```

**Benefits:**
- Fast startup times
- Low memory footprint
- Scales to millions of blocks
- Historical blocks loaded only when accessed

#### Thread Safety

**Per-Chain Locking:**
```java
private final ReentrantLock[] chainLocks = new ReentrantLock[MAX_CHAINS + 1];

// All save/load operations acquire chain-specific lock
chainLocks[chainID.intValue()].lock();
try {
    // Safe operations on this chain
} finally {
    chainLocks[chainID.intValue()].unlock();
}
```

**Why This Matters:**
- Multiple threads can write to different chains concurrently
- Same chain protected from concurrent modification
- No global lock bottleneck

#### Utility Methods (ConnectX)

ConnectX provides convenience methods for blockchain management:

```java
// Get blockchain statistics
BlockchainStats stats = connectX.getBlockchainStats("CXNET");
// or
BlockchainStats stats = connectX.getBlockchainStats(network);

System.out.println(stats); // BlockchainStats{network='CXNET', onDisk=true, c1=5 blocks (current=4), ...}

// Force save all blockchain data
connectX.forceBlockchainSave("CXNET");
// or
connectX.forceBlockchainSave(network);

// Clear blockchain data (testing)
connectX.clearBlockchainData("CXNET");
// or
connectX.clearBlockchainData(network);
```

**BlockchainStats Class:**
```java
public static class BlockchainStats {
    public String networkID;
    public boolean exists;         // On disk?
    public int c1BlockCount;       // Blocks in memory
    public Long c1CurrentBlock;    // Current block ID
    public int c2BlockCount;
    public Long c2CurrentBlock;
    public int c3BlockCount;
    public Long c3CurrentBlock;
}
```

#### Example: Persistence Flow

**Scenario:** EPOCH creates CXNET, records events, restarts

```
Step 1: Network Creation
  → createNetwork("CXNET")
  → Genesis blocks created (block-0)
  → Saved to disk:
    - blockchain/CXNET/chain-1.json (metadata)
    - blockchain/CXNET/chain-2.json (metadata)
    - blockchain/CXNET/chain-3.json (metadata)
    - blockchain/CXNET/blocks/chain-1/block-0.json
    - blockchain/CXNET/blocks/chain-2/block-0.json
    - blockchain/CXNET/blocks/chain-3/block-0.json

Step 2: Record Events (100+ events to c3)
  → recordEvent() x100
  → Block-0 full (100 events)
  → Block-0 saved to disk: blocks/chain-3/block-0.json
  → Block-1 created
  → Chain metadata updated: currentBlockID=1, blockCount=2

Step 3: Restart EPOCH
  → importNetwork() or createNetwork()
  → blockchainPersistence.exists("CXNET") → true
  → Load chains from disk (lazy loading):
    - c1: 1 block in memory (block-0)
    - c2: 1 block in memory (block-0)
    - c3: 1 block in memory (block-1, current)
  → Network ready with full blockchain history on disk
  → Historical blocks loaded on-demand if accessed
```

#### Performance Characteristics

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| Save block | O(1) | Single file write |
| Save metadata | O(1) | Single file write |
| Load chain (lazy) | O(1) | Load metadata + current block only |
| Load chain (all) | O(n) | Load all n blocks from disk |
| Load specific block | O(1) | Direct file access by block ID |

**Storage Overhead:**
- Chain metadata: ~167 bytes per chain
- Genesis block: ~116 bytes (empty)
- Block with events: ~116 bytes + event data
- No duplication of block data between memory and disk

#### Configuration

**Maximum Chains:**
```java
public static final int MAX_CHAINS = 100;
```

**Block Length (events per block):**
```java
network.c1.blockLength = 100; // Default: 100 events
network.c2.blockLength = 100;
network.c3.blockLength = 100;
```

**Lazy Loading:**
```java
// Enabled by default during importNetwork()
NetworkRecord chain = blockchainPersistence.loadChain(networkID, chainID, false);
```

#### Testing

**HTTPBridgeTest Integration:**
EPOCH NMI (HTTPBridgeTest) automatically tests persistence:

```
First Run:
  → Creates CXNET network
  → Persists genesis blocks
  → Output: "No existing blockchain found - creating new CXNET"

Subsequent Runs:
  → Detects existing blockchain on disk
  → Loads persisted chains
  → Output: "✓ CXNET blockchain found on disk - testing persistence"
           "✓ Blockchain loaded from disk"
           "  c1: 1 blocks (current: 0)"
```

No manual intervention required - persistence testing is automatic.

---

## Block Synchronization Protocol

When new nodes join an existing network, they must synchronize the blockchain to rebuild network state. The block sync protocol enables efficient, validated synchronization while maintaining security.

### Sync Overview

**Problem:** New nodes need to catch up with years of blockchain history
**Solution:** Request blocks one-by-one, validate chronologically, rebuild state

### Event Types

#### CHAIN_STATUS_REQUEST
Request current blockchain heights from a peer.

**Payload:**
```json
{
  "network": "CXNET"
}
```

#### CHAIN_STATUS_RESPONSE
Response containing current block heights.

**Payload:**
```json
{
  "c1": 10,
  "c2": 25,
  "c3": 150
}
```

#### BLOCK_REQUEST
Request a specific block from a peer.

**Payload:**
```json
{
  "network": "CXNET",
  "chain": 3,
  "block": 42
}
```

**Process:**
1. Peer checks memory (blockMap) for block
2. If not in memory, loads from disk (lazy loading)
3. Serializes block as JSON
4. Sends BLOCK_RESPONSE

#### BLOCK_RESPONSE
Response containing requested block data.

**Payload:**
```json
["dev.droppinganvil.v3.edge.NetworkBlock", {
  "block": 42,
  "networkEvents": {...}
}]
```

### Synchronization Flow

```
New Node                                  Existing Peer
   |                                            |
   |---(1) CHAIN_STATUS_REQUEST-->             |
   |                                            |
   |<--(2) CHAIN_STATUS_RESPONSE (c3: 150)--   |
   |                                            |
   |---(3) BLOCK_REQUEST (block 0)-->          |
   |<--(4) BLOCK_RESPONSE (block 0)--          |
   |                                            |
   |--- Validate & Apply Block 0               |
   |                                            |
   |---(5) BLOCK_REQUEST (block 1)-->          |
   |<--(6) BLOCK_RESPONSE (block 1)--          |
   |                                            |
   |--- Validate & Apply Block 1               |
   |                                            |
   ... (repeat for blocks 2-150)              ...
   |                                            |
   |--- Blockchain Synced! ---                 |
```

### Chronological Permission Validation

**Critical Security Feature:** Events must be validated against permissions AT THE TIME they were created, not current permissions.

**Why This Matters:**
- Prevents retroactive permission exploits
- Ensures blockchain integrity
- Validates historical state transitions

**Validation Process:**

```java
// 1. Start with genesis permissions
Map<String, Map<String, Entry>> permissionState =
    new HashMap<>(network.networkPermissions.permissionSet);

// 2. Replay all previous blocks to rebuild state
for (NetworkBlock prevBlock : previousBlocks) {
    for (NetworkEvent event : prevBlock.networkEvents.values()) {
        // Check: Did sender have permission AT THIS POINT?
        validatePermission(permissionState, event);

        // Update permission state for next events
        updatePermissionState(permissionState, event);
    }
}

// 3. Validate target block against final permission state
for (NetworkEvent event : block.networkEvents.values()) {
    validatePermission(permissionState, event);
    updatePermissionState(permissionState, event);
}
```

**Example Scenario:**

```
Block 0 (Genesis):
  - NMI creates network
  - Alice given Record-c3 permission

Block 1:
  - Alice records event A (VALID - she has permission)
  - Alice records event B (VALID - she has permission)

Block 2:
  - NMI revokes Alice's Record-c3 permission
  - Alice records event C (INVALID - permission revoked)

Block 3 (Today):
  - Alice records event D (INVALID - no permission)

When syncing:
  - Events A & B validate (Alice had permission at Block 1)
  - Event C fails validation (Alice permission revoked at Block 2)
  - Event D fails validation (Alice has no permission)
```

This prevents an attacker from:
1. Gaining permission temporarily
2. Creating malicious events
3. Permission being revoked
4. Claiming old malicious events are valid

### Event Execution During Sync

**The executeOnSync Flag:**

Not all events should be executed when syncing old blocks. NetworkEvent contains a boolean field to differentiate:

```java
public boolean executeOnSync = false;
```

**State-Modifying Events (executeOnSync = true):**
- Permission changes (UPDATE_NMI, ADD_NMI, DELETE_NMI)
- Network configuration updates
- Backend node additions/removals
- **MUST** be executed during sync to rebuild state correctly

**Ephemeral Events (executeOnSync = false):**
- Messages (MESSAGE)
- Pings (PeerFinding)
- Resource availability updates
- Should **NOT** be executed during sync (realtime only)

**Example:**

```java
// During sync, processing Block 42 from 2 years ago
for (NetworkEvent event : block.networkEvents.values()) {
    if (event.executeOnSync) {
        // Execute: Permission changes, NMI updates
        processStateEvent(event);
    } else {
        // Skip: 2-year-old messages, pings
        // User doesn't want to see ancient messages
    }
}
```

**Benefits:**
- Efficient sync (skip irrelevant events)
- Correct state reconstruction (process all state changes)
- Better user experience (no ancient messages)

### Sync Strategy

**Recommended Approach:**

1. **Query Chain Status**
   ```java
   sendChainStatusRequest("CXNET");
   // Response: {c1: 10, c2: 25, c3: 150}
   ```

2. **Identify Gaps**
   ```java
   int localHeight = network.c3.current.block;  // e.g., 0
   int remoteHeight = 150;
   int blocksToSync = remoteHeight - localHeight;  // 150 blocks
   ```

3. **Request Blocks Sequentially**
   ```java
   for (long blockID = localHeight + 1; blockID <= remoteHeight; blockID++) {
       sendBlockRequest("CXNET", 3, blockID);
       // Wait for BLOCK_RESPONSE
       // Validate chronologically
       // Apply to local blockchain
   }
   ```

4. **Verify Final State**
   ```java
   // Compare final block heights with peer
   // Verify merkle roots (if implemented)
   // Sync complete!
   ```

**Optimization:**
- Request from multiple peers in parallel (Byzantine fault tolerance)
- Verify blocks match across peers before applying
- Cache commonly requested blocks
- Prioritize critical chains (c1 > c2 > c3)

---

## Multi-Path Routing & LAN Discovery

### Overview

ConnectX implements **multi-path routing** with automatic **LAN peer discovery**. Events are transmitted through ALL available routes simultaneously for maximum reliability, performance, and firewall traversal.

**Implementation Date:** 2025-12-25
**Version:** 1.0
**Status:** Production Ready

### Architecture

#### Address Discovery

**Passive Discovery (Automatic):**
- Every incoming event automatically records the source address
- Implemented in `NodeMesh.processNetworkInput()` (lines 325-330)
- Stores ALL addresses (LAN, WAN, bridge sources)
- No configuration required - works automatically

**Active Discovery (LAN Scanner):**
- Scans local subnet on startup (2-second delay)
- Probes all IPs in subnet for ConnectX peers
- Sends CXHELLO requests to discovered peers
- Runs asynchronously in background
- Configurable timeout and concurrency

**Persistent Storage:**
- All discovered addresses stored in `DataContainer.localPeerAddresses`
- Map format: `peerID -> "IP:port"`
- Persists across restarts via DataContainer serialization
- Not included in network seeds (local only)

#### Multi-Path Transmission Strategy

**For Single Peer (CXS Scope):**
OutConnectionController tries ALL routes in parallel:

1. **HTTP Bridge** (from `node.addr`)
   - If `node.addr` = `"cxHTTP1:https://..."`
   - Uses bridge provider to send

2. **CXPath Bridge** (from `event.p.bridge`)
   - If event path has bridge info
   - Provides fallback bridge route

3. **Direct LAN** (from DataContainer)
   - Checks `dataContainer.getLocalPeerAddress(peerID)`
   - Direct socket connection if available
   - Fastest route for local peers

**Result:** `[Multi-Path] Sent to abcd1234 via: cxHTTP1+LAN-Direct`

**For Broadcast (CXN Scope):**
For each peer in broadcast, tries:

1. **HTTP Bridge** (if available)
2. **P2P Direct Socket** (if IP:port address)
3. **LAN Address** (from DataContainer)

**Result:** `[Multi-Path CXN] abcd1234 via: cxHTTP1+LAN`

### Event Types

#### CXHELLO
**Description:** LAN peer discovery request
**Payload:**
```json
{
  "peerID": "uuid-of-sender",
  "port": 49152,
  "requestID": "request-uuid"
}
```
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Sent to discover peers on LAN. Peer responds with CXHELLO_RESPONSE. Address automatically recorded by passive discovery.

#### CXHELLO_RESPONSE
**Description:** Response to CXHELLO request
**Payload:**
```json
{
  "peerID": "uuid-of-responder",
  "port": 49152,
  "requestID": "original-request-uuid",
  "networks": []
}
```
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Confirms peer identity and port. Address already recorded by passive discovery.

### Benefits

**Firewall Traversal:**
- Peers behind NATs can communicate if on same LAN
- Direct local connections bypass firewall rules
- HTTP bridges provide WAN fallback

**Performance:**
- Local connections: ~1ms latency
- HTTP bridge: ~30-100ms latency
- Automatically uses fastest available route
- Reduces load on HTTP bridges

**Reliability:**
- Multiple routes provide redundancy
- If bridge fails, P2P/LAN still works
- If LAN fails, bridge still works
- Events sent through ALL routes simultaneously

**Zero Configuration:**
- Works automatically with no setup
- Discovers peers passively and actively
- Persists across restarts

**Scalability:**
- Efficient parallel scanning (30 threads max)
- Only scans local subnet (not entire internet)
- Passive discovery has zero overhead
- Reduces centralized bridge server load

### LANScanner Implementation

**Class:** `dev.droppinganvil.v3.network.nodemesh.LANScanner`

**Key Methods:**
- `getLocalIP()`: Gets non-loopback IPv4 address
- `getSubnetMask()`: Determines subnet (defaults /24)
- `getIPRange()`: Calculates all IPs in subnet
- `isHostReachable()`: Tests if port is open (300ms timeout)
- `sendCXHELLO()`: Sends discovery request
- `scanNetwork()`: Full subnet scan (parallel threads)
- `broadcastHello()`: UDP broadcast alternative

**Scan Process:**
1. Detect local IP and subnet
2. Calculate IP range (up to 254 hosts)
3. Parallel probe (30 threads max)
4. Send CXHELLO to responsive hosts
5. Report results

**Example Output:**
```
[LAN Scanner] ========================================
[LAN Scanner] Starting active LAN peer discovery...
[LAN Scanner] ========================================
[LAN Scanner] Local IP: 192.168.1.100/24
[LAN Scanner] Scanning 254 addresses on port 49152...
[LAN Scanner] ✓ Found active peer at 192.168.1.101:49152
[LAN Scanner] ✓ Found active peer at 192.168.1.102:49152
[LAN Scanner] Progress: 50/254 (2 discovered)
[LAN Scanner] Progress: 254/254 (2 discovered)
[LAN Scanner] ========================================
[LAN Scanner] Scan complete!
[LAN Scanner] Found 2 active peers on local network
[LAN Scanner] Total addresses in DataContainer: 5
[LAN Scanner] ========================================
```

### Usage

**Automatic (Default):**
```java
ConnectX cx = new ConnectX("./ConnectX-Peer1", 49152);
// LAN scan starts automatically after 2 seconds
// All incoming events record source addresses
// All outgoing events use multi-path routing
```

**Check Discovered Peers:**
```java
Map<String, String> peers = cx.dataContainer.getLocalPeerAddresses();
for (Map.Entry<String, String> entry : peers.entrySet()) {
    System.out.println("Peer " + entry.getKey() + " @ " + entry.getValue());
}
```

**Get Address for Peer:**
```java
String address = cx.dataContainer.getLocalPeerAddress(peerID);
if (address != null) {
    System.out.println("Can reach peer directly at: " + address);
}
```

### Configuration

**Disable LAN Scanner:**
Remove LAN scanner initialization from `ConnectX.connect()` (lines 1072-1086)

**Change Scan Delay:**
```java
Thread.sleep(5000); // 5 seconds instead of 2
```

**Change Concurrent Threads:**
```java
if (scanThreads.size() >= 50) { // 50 instead of 30
```

**Change Scan Timeout:**
```java
socket.connect(new InetSocketAddress(ip, port), 500); // 500ms
```

### Security Considerations

**Passive Discovery:**
- Only records addresses from authenticated events
- Signature verification happens before recording
- Malicious hosts cannot inject fake addresses

**Active Scanning:**
- Only scans local subnet (RFC 1918 addresses)
- CXHELLO requires valid signature
- Non-ConnectX hosts safely ignored

**Address Storage:**
- DataContainer encrypted at rest
- Only accessible by local peer
- Not included in seeds (not distributed)

### Implementation Files

**Modified Files:**
- `EventType.java`: Added CXHELLO and CXHELLO_RESPONSE event types
- `DataContainer.java`: Added `localPeerAddresses` Map with helper methods
- `NodeMesh.java`: Passive address recording, CXHELLO handlers
- `OutConnectionController.java`: Multi-path routing for CXS and CXN scopes
- `ConnectX.java`: Auto-starts LAN scanner 2 seconds after network init

**New Files:**
- `LANScanner.java`: Active LAN discovery implementation

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

## Event Types Reference

This section provides a comprehensive reference for all EventTypes in the ConnectX protocol.

### Core Events

#### `NewNode`
**Description:** Device joined CX network
**Payload:** Serialized `Node` object (cxJSON1)
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Announces a new node's presence to the network. Other nodes cache the public key for future encrypted communication.

#### `MESSAGE`
**Description:** Internal messaging between nodes
**Payload:** UTF-8 encoded message text
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Real-time communication between nodes. Not persisted to blockchain as messages are ephemeral.

#### `HELLOWORLD`
**Description:** Initial handshake/test transmission
**Payload:** Varies (used for testing)
**Recorded To:** Not recorded to blockchain
**ExecuteOnSync:** `false`
**Purpose:** Used when transmitting initial data or testing connectivity.

### Resource & Network Management

#### `GLOBALRESOURCEUPDATE`
**Description:** Resource common to all nodes has been updated
**Payload:** Resource update data
**Recorded To:** Chain 2 (c2 - Resources)
**ExecuteOnSync:** `true`
**Purpose:** Notify all nodes of global resource changes (e.g., shared configuration files).

#### `ResourceModification`
**Description:** Resource has been modified
**Payload:** Resource modification details
**Recorded To:** Chain 2 (c2 - Resources)
**ExecuteOnSync:** `true`
**Purpose:** Track changes to network resources.

#### `DICTIONARYEDIT`
**Description:** Network dictionary metadata edited
**Payload:** Dictionary modification data
**Recorded To:** Chain 1 (c1 - Admin)
**ExecuteOnSync:** `true`
**Purpose:** Update network metadata and configuration.

#### `RESTART`
**Description:** Schedules a network restart
**Payload:** Restart schedule data
**Recorded To:** Chain 1 (c1 - Admin)
**ExecuteOnSync:** `true`
**Purpose:** Coordinate network-wide restarts or maintenance windows.

### Bootstrap & Discovery

#### `SEED_REQUEST`
**Description:** Request official network seed from NMI
**Payload:** None (empty or network ID)
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** New nodes request bootstrap seed from NMI/infrastructure nodes to join CXNET.

#### `SEED_RESPONSE`
**Description:** Response to SEED_REQUEST containing seed data
**Payload:** Serialized `Seed` object (cxJSON1)
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Provides bootstrap seed containing network metadata, peer list, and certificates.

#### `PeerFinding`
**Description:** Legacy peer discovery request
**Payload:** Peer discovery data
**Recorded To:** Not recorded to blockchain
**ExecuteOnSync:** `false`
**Purpose:** Request peer information for network discovery. **Note:** Consider using `PEER_LIST_REQUEST` for modern implementations.

#### `PEER_LIST_REQUEST`
**Description:** Request random peer IPs for discovery
**Payload:** JSON `{"count": 10}` (optional, defaults to 30% of known peers or max 10)
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Rate Limited:** 3 requests per IP per hour per node
**Purpose:** Modern peer discovery. Requesting node receives only IP addresses, must then contact each IP for full Node info/seed. This prevents exposing full network topology to untrusted nodes.

#### `PEER_LIST_RESPONSE`
**Description:** Response to PEER_LIST_REQUEST
**Payload:** JSON `{"ips": ["192.168.1.100:49152", "10.0.0.5:49153", ...]}`
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Returns IP:port combinations for peer discovery. Receiving node must manually request `NewNode`/`SEED` from each IP.

### NMI Management

#### `UPDATE_NMI`
**Description:** Update existing Network Master Identity (NMI)
**Payload:** New NMI credentials
**Recorded To:** Chain 1 (c1 - Admin)
**ExecuteOnSync:** `true`
**Permission Required:** Existing NMI signature
**Purpose:** Allows networks to update their NMI credentials (e.g., key rotation). **Note:** CXNET NMI is immutable (exception conditions to be added later).

#### `ADD_NMI`
**Description:** Add new Network Master Identity (NMI)
**Payload:** New NMI credentials
**Recorded To:** Chain 1 (c1 - Admin)
**ExecuteOnSync:** `true`
**Permission Required:** Existing NMI signature
**Purpose:** Allows networks to add additional NMIs for redundancy and failover.

#### `DELETE_NMI`
**Description:** Delete Network Master Identity (NMI)
**Payload:** NMI identifier to remove
**Recorded To:** Chain 1 (c1 - Admin)
**ExecuteOnSync:** `true`
**Permission Required:** Existing NMI signature
**Purpose:** Allows networks to remove NMIs that are no longer needed or compromised.

### Blockchain Synchronization

#### `CHAIN_STATUS_REQUEST`
**Description:** Request blockchain metadata (current block heights)
**Payload:** JSON `{"network": "NETWORKID"}`
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** New/syncing nodes request current blockchain heights to determine sync status.

#### `CHAIN_STATUS_RESPONSE`
**Description:** Response to CHAIN_STATUS_REQUEST
**Payload:** JSON `{"c1": 10, "c2": 25, "c3": 150}`
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Returns current block height for each chain. Requesting node compares with local heights to identify gaps.

#### `BLOCK_REQUEST`
**Description:** Request a specific block from a peer
**Payload:** JSON `{"network": "NETWORKID", "chain": 3, "block": 5}`
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Request specific block data for blockchain synchronization. Nodes sync blocks one-by-one for validation.

#### `BLOCK_RESPONSE`
**Description:** Response to BLOCK_REQUEST containing block data
**Payload:** Serialized `NetworkBlock` object (cxJSON1)
**Recorded To:** Not recorded to blockchain (ephemeral)
**ExecuteOnSync:** `false`
**Purpose:** Delivers requested block data. Receiving node validates chronologically and applies to local blockchain.

### Access Control & Moderation

#### `BLOCK_NODE`
**Description:** Block a node from the network (CXNET-level or network-specific)
**Payload:** JSON `{"network": "NETWORKID", "nodeID": "UUID", "reason": "spam"}`
**Recorded To:** Chain 1 (c1 - Admin)
**ExecuteOnSync:** `true`
**Permission Required:** `BlockNode` (NMI-only or designated moderators)
**Purpose:**
- If `network` is `"CXNET"`: Blocks at CXNET level (all transmissions rejected)
- If `network` is specific network ID: Blocks only from that network
**Security:** State-modifying event that must be replayed during blockchain sync to rebuild block lists correctly.

#### `UNBLOCK_NODE`
**Description:** Unblock a previously blocked node
**Payload:** JSON `{"network": "NETWORKID", "nodeID": "UUID"}`
**Recorded To:** Chain 1 (c1 - Admin)
**ExecuteOnSync:** `true`
**Permission Required:** `UnblockNode` (NMI-only or designated moderators)
**Purpose:** Reverses `BLOCK_NODE` action, restoring node's network access.

#### `REGISTER_NODE`
**Description:** Register a node to a network (for whitelist/private networks)
**Payload:** JSON `{"network": "NETWORKID", "nodeID": "UUID", "approver": "APPROVER_UUID"}`
**Recorded To:** Chain 1 (c1 - Admin) **[SOURCE OF TRUTH]**
**ExecuteOnSync:** `true`
**Permission Required:** `RegisterNode` (NMI-only or designated approvers)
**Purpose:** Explicitly approve a node for network membership. System reads c1 blockchain to populate approved nodes list.

**Whitelist Mode Behavior:**
- Nodes cannot join network unless `REGISTER_NODE` event exists in c1
- During bootstrap, system checks c1 for registration entry
- If not found and whitelist mode enabled: **reject connection**

---

## Permissions Reference

This section provides a comprehensive reference for all Permission types in the ConnectX protocol.

### Network Operations

#### `AddResource`
**Description:** Permission to add new resources to the network
**Typical Weight:** 50-100
**Granted To:** NMI, backend infrastructure nodes, trusted contributors
**Purpose:** Control who can upload new resources to the network resource pool.

#### `NetworkUpload`
**Description:** Permission to upload data to the network
**Typical Weight:** 30-100
**Granted To:** NMI, backend nodes, regular members (lower weight)
**Purpose:** General upload permission for network data.

#### `UploadGlobalResource`
**Description:** Permission to upload resources accessible to all nodes
**Typical Weight:** 70-100
**Granted To:** NMI, designated administrators
**Purpose:** Control who can create/update global resources (e.g., network configuration, shared libraries).

#### `AddAccount`
**Description:** Permission to add new accounts/nodes to the network
**Typical Weight:** 50-100 (NMI), 10 (default nodes)
**Granted To:**
- **NMI:** Weight 100 (full control)
- **DEFAULT_NODE:** Weight 10 (CXNET: all networks get this)
- **Regular nodes:** Weight 10 (non-CXNET networks only)
**Purpose:** Control network membership. In CXNET, all nodes can add accounts. In custom networks, this can be restricted.

### Blockchain & Data

#### `Record`
**Description:** Permission to record events to blockchain chains
**Typical Weight:** 30-100
**Granted To:** Varies by chain and network policy
**Format:** `"Record-{chainID}"` (e.g., `"Record-1"`, `"Record-2"`, `"Record-3"`)
**Purpose:** Control who can write to specific blockchain chains.

**Chain-Specific Recording:**
- `Record-1` (c1): Admin events (permissions, NMI updates, network config)
- `Record-2` (c2): Resource events (file uploads, resource modifications)
- `Record-3` (c3): General events (messages, user activities)

**Network-Specific Defaults:**
- **CXNET:** Only `AddAccount` permission granted (NO blockchain recording by default)
- **Custom Networks:** `AddAccount` + `Record-3` permissions granted

**Purpose:** Protect blockchain integrity by limiting who can write to each chain.

#### `Transmit`
**Description:** Permission to transmit data through the network
**Typical Weight:** 10-100
**Granted To:** Most nodes (fundamental communication right)
**Purpose:** Control basic network communication capability.

### Moderation & Security

#### `BlockNode`
**Description:** Permission to block nodes at network level
**Typical Weight:** 100 (NMI-only typically)
**Granted To:** NMI, designated moderators (with high weight)
**Purpose:** Protect network from spam, abuse, or malicious nodes.

**Blocking Levels:**
- **CXNET-level:** Blocks all transmissions (network-wide ban)
- **Network-specific:** Blocks only from specific network

**Event:** `BLOCK_NODE` (recorded to c1, executeOnSync = true)

#### `UnblockNode`
**Description:** Permission to unblock previously blocked nodes
**Typical Weight:** 100 (NMI-only typically)
**Granted To:** NMI, designated moderators
**Purpose:** Restore network access to blocked nodes after review/appeal.

**Event:** `UNBLOCK_NODE` (recorded to c1, executeOnSync = true)

### Whitelist/Private Networks

#### `RegisterNode`
**Description:** Permission to register nodes to a network (whitelist/private networks)
**Typical Weight:** 100 (NMI-only typically)
**Granted To:** NMI, designated approvers
**Purpose:** Explicitly approve nodes for network membership in whitelist mode.

**How It Works:**
1. Approver creates `REGISTER_NODE` event with target node UUID
2. Event recorded to c1 (Admin) chain
3. System reads c1 to populate approved nodes list
4. During bootstrap/connection, system checks c1 for registration
5. If not found and whitelist mode enabled: **connection rejected**

**Event:** `REGISTER_NODE` (recorded to c1, executeOnSync = true)

**Use Cases:**
- Private corporate networks (employee-only access)
- Invite-only communities
- Paid/subscription networks (register after payment verification)
- High-security networks (manual vetting required)

---

## DataContainer - Local State Management

### Overview

**DataContainer** is a local encrypted storage system for network state that should NOT be distributed in network seeds. It stores ephemeral and sensitive data that is specific to each node's perspective of the network.

**Location:** `{cxRoot}/data.cxd`
**Format:** JSON (cxJSON1 serialization)
**Encryption:** Optional (recommended for production)

### Purpose

**Why Separate from Seeds?**
- **Security:** Registration tokens should never be distributed
- **Privacy:** Local block lists are node-specific
- **Scalability:** Registered nodes list derived from blockchain (c1), not static storage
- **Flexibility:** Nodes can have different local policies

### Data Structure

```java
public class DataContainer {
    // Whitelist/Registration Management
    public Map<String, Set<String>> networkRegisteredNodes;
    // networkID → Set of node UUIDs

    // Block List Management (local perspective)
    public Map<String, Map<String, String>> networkBlockedNodes;
    // networkID → (nodeID → reason)

    // Token-Based Registration
    public Map<String, String> registrationTokens;
    // token → nodeID (one-time use)
}
```

### Token-Based Registration System

**Problem:** How do whitelist networks approve new members without manually adding UUIDs to blockchain?

**Solution:** Backend generates one-time use tokens that nodes present during registration.

#### Registration Flow

```
Step 1: User requests access to whitelist network
  ↓
Step 2: Backend verifies request (out-of-band: payment, email verification, etc.)
  ↓
Step 3: Backend generates registration token
  → token = UUID.randomUUID().toString()
  → registrationTokens.put(token, requestingNodeID)
  → dataContainer saved to disk
  ↓
Step 4: Backend sends token to user (email, SMS, web portal, etc.)
  ↓
Step 5: User's node sends REGISTER_NODE event with token
  → Payload: {"network": "TESTNET", "nodeID": "abc-123", "token": "xyz-789"}
  ↓
Step 6: Backend validates token
  → Check: registrationTokens.get(token) == nodeID?
  → If valid: Process registration (record to c1 blockchain)
  → If invalid: Reject
  → Remove token (one-time use)
  ↓
Step 7: Registration recorded to c1 (Admin) chain
  → REGISTER_NODE event persisted
  → Event has executeOnSync=true
  → During blockchain sync, all nodes rebuild registered nodes list from c1
```

#### Security Features

**One-Time Use Tokens:**
```java
public boolean registerNode(String networkID, String nodeID, String token) {
    // Verify token exists and matches nodeID
    if (!registrationTokens.containsKey(token)) {
        return false; // Token not found
    }

    String expectedNodeID = registrationTokens.get(token);
    if (!expectedNodeID.equals(nodeID)) {
        return false; // Token doesn't match requesting node
    }

    // Token is valid - consume it (one-time use)
    registrationTokens.remove(token);

    // Add to registered nodes (local cache)
    networkRegisteredNodes
        .computeIfAbsent(networkID, k -> new HashSet<>())
        .add(nodeID);

    // Save DataContainer to persist token removal
    connectX.saveDataContainer();

    return true;
}
```

**Benefits:**
- **Scalability:** Backends can pre-generate tokens for batch registrations
- **Flexibility:** Different token distribution methods (email, QR codes, etc.)
- **Auditability:** Token generation/use tracked in backend logs
- **Security:** Tokens cannot be reused or guessed

### Registered Nodes Management

**Source of Truth:** Chain 1 (c1 - Admin) blockchain
**Local Cache:** `networkRegisteredNodes` in DataContainer

```java
// Check if node is registered (local cache)
public boolean isNodeRegistered(String networkID, String nodeID) {
    Set<String> registered = networkRegisteredNodes.get(networkID);
    return registered != null && registered.contains(nodeID);
}

// During blockchain sync: Rebuild registered nodes from c1
public void rebuildRegisteredNodesFromBlockchain(CXNetwork network) {
    Set<String> registered = new HashSet<>();

    // Scan all c1 blocks for REGISTER_NODE events
    for (NetworkBlock block : network.c1.getAllBlocks()) {
        for (NetworkEvent event : block.networkEvents.values()) {
            if (event.eT.equals("REGISTER_NODE")) {
                Map<String, Object> payload = deserialize(event.d);
                String nodeID = (String) payload.get("nodeID");
                registered.add(nodeID);
            }
        }
    }

    // Update local cache
    networkRegisteredNodes.put(network.networkDictionary.networkID, registered);
    saveDataContainer();
}
```

### Block List Management

**Two-Tier Blocking:**

1. **CXNET-Level Blocking** (Global Ban)
   - Recorded to CXNET c1 chain
   - Blocks ALL transmissions from node
   - Enforced by all CXNET participants

2. **Network-Specific Blocking**
   - Recorded to specific network's c1 chain
   - Blocks only within that network
   - Other networks unaffected

```java
// Block a node (local enforcement + blockchain recording)
public void blockNode(String networkID, String nodeID, String reason) {
    networkBlockedNodes
        .computeIfAbsent(networkID, k -> new HashMap<>())
        .put(nodeID, reason);

    // Also record BLOCK_NODE event to c1 blockchain
    connectX.recordBlockNodeEvent(networkID, nodeID, reason);
}

// Check if node is blocked
public boolean isNodeBlocked(String networkID, String nodeID) {
    Map<String, String> blocked = networkBlockedNodes.get(networkID);
    return blocked != null && blocked.containsKey(nodeID);
}

// Unblock a node
public String unblockNode(String networkID, String nodeID) {
    Map<String, String> blocked = networkBlockedNodes.get(networkID);
    if (blocked == null) return null;

    String reason = blocked.remove(nodeID);

    // Also record UNBLOCK_NODE event to c1 blockchain
    if (reason != null) {
        connectX.recordUnblockNodeEvent(networkID, nodeID);
    }

    return reason; // Return original block reason
}
```

### Whitelist Enforcement

**Enforcement Point:** NodeMesh.processNetworkInput (line 299-322)

```java
// Check whitelist BEFORE processing event
CXNetwork network = ConnectX.getNetwork(networkID);
if (network != null && network.configuration.whitelistMode) {
    // Check if transmitter is registered
    boolean isRegistered = connectX.dataContainer.isNodeRegistered(
        networkID,
        transmitterID
    );

    if (!isRegistered) {
        System.out.println("[WHITELIST] Rejected transmission from unregistered node: "
            + transmitterID);
        Analytics.addData(AnalyticData.Tear,
            "Whitelist rejection: " + transmitterID + " not registered to " + networkID);
        return; // Drop transmission
    }
}

// Node is registered or network not whitelisted - proceed
```

**Result:** Unregistered nodes' transmissions silently dropped at network layer.

### Persistence

**Automatic Save Triggers:**
- Token generation/consumption
- Node registration/unregistration
- Node blocking/unblocking
- Any DataContainer modification

```java
// Save DataContainer to disk
public void saveDataContainer() throws Exception {
    File dataFile = new File(cxRoot, "data.cxd");
    String json = serialize("cxJSON1", dataContainer);

    FileWriter writer = new FileWriter(dataFile);
    writer.write(json);
    writer.flush();
    writer.close();
}

// Load DataContainer from disk (during initialization)
private void loadDataContainer() {
    File dataFile = new File(cxRoot, "data.cxd");
    if (!dataFile.exists()) {
        dataContainer = new DataContainer();
        return;
    }

    FileInputStream fis = new FileInputStream(dataFile);
    dataContainer = (DataContainer) deserialize(
        "cxJSON1",
        fis,
        DataContainer.class
    );
}
```

### Periodic Backend Synchronization

**Purpose:** Keep backend nodes synchronized on whitelist state

**Implementation:**
```java
// Run every 10 minutes
Thread syncThread = new Thread(() -> {
    while (true) {
        Thread.sleep(10 * 60 * 1000); // 10 minutes

        // Send CHAIN_STATUS_REQUEST to all backend peers
        for (String backendNodeID : network.configuration.backendSet) {
            Map<String, Object> req = new HashMap<>();
            req.put("network", networkID);
            String reqJson = serialize("cxJSON1", req);

            buildEvent(EventType.CHAIN_STATUS_REQUEST, reqJson.getBytes())
                .toPeer(backendNodeID)
                .toNetwork(networkID)
                .queue();
        }
    }
});
syncThread.setDaemon(true);
syncThread.start();
```

**Benefits:**
- Backends detect registration events from other backends
- Eventual consistency across infrastructure nodes
- No single point of failure

---

## Network Types

ConnectX supports three fundamental network types, each with different access control and membership models.

### Open Networks

**Description:** Public networks with minimal barriers to entry. Any node can join and participate.

**Characteristics:**
- **Whitelist Mode:** Disabled
- **Default Permissions:** Broad (AddAccount, Record-c3, Transmit)
- **Registration:** None required
- **Moderation:** Post-hoc (block malicious nodes after detection)
- **Membership:** Automatic upon bootstrap

**Example Configuration:**
```java
CXNetwork network = new CXNetwork("PUBLIC_CHAT", nmiPublicKey);
network.configuration.whitelistMode = false;

// Grant broad default permissions
network.networkPermissions.addEntry("DEFAULT_NODE", Permission.AddAccount, 10);
network.networkPermissions.addEntry("DEFAULT_NODE", Permission.Record-3, 10);
network.networkPermissions.addEntry("DEFAULT_NODE", Permission.Transmit, 10);
```

**Use Cases:**
- Public chat networks
- Open-source project collaboration
- Community forums
- Public file sharing

**Benefits:**
- Easy onboarding (no approval needed)
- Maximum network growth
- Minimal administrative overhead

**Challenges:**
- Spam/abuse potential (mitigated by BLOCK_NODE)
- Quality control difficulty
- Resource consumption from untrusted nodes

**Moderation Strategy:**
- Monitor network activity
- Use `BLOCK_NODE` to ban malicious actors
- Implement rate limiting (e.g., PEER_LIST_REQUEST: 3/hour per IP)
- Use blockchain to track abuse patterns

### Whitelist Networks

**Description:** Semi-private networks where nodes must be explicitly approved before joining.

**Characteristics:**
- **Whitelist Mode:** Enabled
- **Default Permissions:** Minimal (typically no blockchain recording)
- **Registration:** Required via `REGISTER_NODE` event
- **Moderation:** Pre-emptive (approve before access)
- **Membership:** Manual approval process

**Example Configuration:**
```java
CXNetwork network = new CXNetwork("APPROVED_USERS", nmiPublicKey);
network.configuration.whitelistMode = true;

// Minimal default permissions (connection only)
network.networkPermissions.addEntry("DEFAULT_NODE", Permission.Transmit, 5);

// NMI can register nodes
network.networkPermissions.addEntry(nmiUUID, Permission.RegisterNode, 100);
```

**Registration Flow:**
1. Node requests to join network
2. Administrator reviews request (out-of-band)
3. Administrator creates `REGISTER_NODE` event:
   ```json
   {
     "network": "APPROVED_USERS",
     "nodeID": "requesting-node-uuid",
     "approver": "nmi-uuid"
   }
   ```
4. Event recorded to c1 (Admin) chain
5. Node can now successfully bootstrap into network

**Use Cases:**
- Paid subscription services (register after payment)
- Employee-only corporate networks
- Verified user communities
- Educational platforms (register enrolled students)

**Benefits:**
- Quality control (vet members before access)
- Reduced spam/abuse
- Compliance (know your users)
- Resource protection

**Challenges:**
- Administrative overhead (manual approvals)
- Slower growth
- User friction (waiting for approval)

**Moderation Strategy:**
- Pre-approval vetting process
- Still use `BLOCK_NODE` for post-approval issues
- Review c1 chain for registration audit trail
- Can revoke access without unregistering (just block)

### Private Networks

**Description:** Highly restricted networks with strict access control and limited visibility.

**Characteristics:**
- **Whitelist Mode:** Enabled
- **Default Permissions:** None (zero default access)
- **Registration:** Required + additional verification
- **Moderation:** Strict pre-emptive control
- **Membership:** Invitation-only, verified identities
- **Visibility:** May not appear in public seed distribution

**Example Configuration:**
```java
CXNetwork network = new CXNetwork("EXECUTIVE_BOARD", nmiPublicKey);
network.configuration.whitelistMode = true;
network.configuration.publicSeed = false; // Don't distribute in public seeds

// No default permissions at all
// Every member must be explicitly granted permissions

// NMI full control
network.networkPermissions.addEntry(nmiUUID, Permission.RegisterNode, 100);
network.networkPermissions.addEntry(nmiUUID, Permission.BlockNode, 100);
network.networkPermissions.addEntry(nmiUUID, Permission.Record-1, 100);
// ... all permissions at weight 100

// Manually add each approved member
network.networkPermissions.addEntry(approvedUserUUID, Permission.Transmit, 50);
network.networkPermissions.addEntry(approvedUserUUID, Permission.Record-3, 30);
```

**Registration Flow:**
1. Invitation sent out-of-band (email, secure channel)
2. Recipient provides node UUID
3. Identity verification (may involve KYC, credentials, etc.)
4. Administrator creates `REGISTER_NODE` event
5. Administrator grants specific permissions for that node UUID
6. Node receives private seed data (not in public distribution)
7. Node can bootstrap using private seed

**Use Cases:**
- Corporate executive communications
- Government/military secure networks
- Financial institution inter-bank networks
- Healthcare HIPAA-compliant networks
- Legal attorney-client privileged communications

**Benefits:**
- Maximum security and privacy
- Full control over membership
- Audit trail of all access (c1 blockchain)
- Can enforce strict identity requirements

**Challenges:**
- High administrative burden
- Scalability limitations
- Complex onboarding process
- Requires secure out-of-band communication

**Moderation Strategy:**
- Multi-layer approval process
- Regular access reviews
- Immediate `BLOCK_NODE` for violations
- Audit c1 chain for compliance
- May require additional authentication layers (beyond CX protocol)

### Comparison Matrix

| Feature | Open Network | Whitelist Network | Private Network |
|---------|-------------|-------------------|-----------------|
| **Whitelist Mode** | Disabled | Enabled | Enabled |
| **Registration Required** | No | Yes | Yes + Verification |
| **Default Permissions** | Broad | Minimal | None |
| **Public Seed Distribution** | Yes | Yes | No |
| **Approval Process** | None | Manual (REGISTER_NODE) | Strict Multi-Layer |
| **Growth Rate** | Fast | Moderate | Slow |
| **Administrative Overhead** | Low | Medium | High |
| **Security Level** | Basic (post-hoc moderation) | Medium (pre-approval) | Maximum (strict control) |
| **Use Case Example** | Public Chat | Paid Service | Corporate Secure |

### Network Type Selection Guide

**Choose Open Network When:**
- Building public community/social platform
- Want rapid user growth
- Can tolerate some spam/abuse
- Focus on accessibility over control
- Have robust moderation tools

**Choose Whitelist Network When:**
- Need quality control
- Operating paid/subscription model
- Want to verify users before access
- Regulatory requirements (KYC, etc.)
- Balance between growth and control

**Choose Private Network When:**
- Handling sensitive/confidential data
- Regulatory compliance critical (HIPAA, GDPR, etc.)
- Need strict identity verification
- Security is paramount
- Limited membership is acceptable
- Can manage complex onboarding

### Implementation Notes

**Switching Network Types:**

Networks can transition between types, but this requires careful planning:

**Open → Whitelist:**
1. Enable `whitelistMode` in configuration
2. Create `REGISTER_NODE` events for all existing members (recorded to c1)
3. New members now require registration
4. Existing unregistered members will be blocked on next sync

**Whitelist → Private:**
1. Already have `whitelistMode` enabled
2. Disable public seed distribution
3. Review and revoke broad default permissions
4. Grant permissions individually per node UUID
5. Distribute seeds through private channels only

**Private/Whitelist → Open:**
1. Disable `whitelistMode` in configuration
2. Grant broad default permissions
3. Enable public seed distribution
4. **Warning:** Cannot retroactively hide previous registrations (c1 blockchain is immutable)

**Blockchain Considerations:**

All network type transitions must be recorded to c1 (Admin) chain:
- Configuration changes (whitelistMode toggle)
- Permission updates (default permission grants/revokes)
- Registration events (REGISTER_NODE)

This provides an **immutable audit trail** of network access policy changes.

---

*ConnectX Protocol v3.0 - Decentralized, Secure, Private*