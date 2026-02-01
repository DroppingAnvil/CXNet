# ConnectX (CX) Protocol Documentation

**Version:** 3.1
**Last Updated:** 2025-01-01 (Zero Trust & Consensus)

---

## Recent Updates (v3.1)

### Zero Trust Mode
- Irreversible network decentralization
- NMI permission blocking when zT=true
- ZERO_TRUST_ACTIVATION event type
- Complete transition from centralized to P2P governance

### Block Consensus Mechanism
- Hybrid consensus (EPOCH/NMI-trust + multi-peer voting)
- BlockConsensusTracker for majority voting
- Per-network consensus configuration
- Byzantine fault tolerance through supermajority

### Blockchain Improvements
- Signed event blob architecture
- Blockchain recording at transmission time
- Per-chain permission system (Record-1, Record-2, Record-3)
- Automatic state replay from blockchain

### Network Improvements
- Hybrid node addressing (P2P + bridge-only nodes)
- Removed hardcoded localhost for distributed testing
- Address bloat prevention (single receiving socket per peer)
- LAN discovery via CXHELLO protocol

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

## Cryptographic Verification Implementation

**Implementation Date:** 2025-12-29
**Version:** 3.0.1
**Status:** Production Ready

### Overview

ConnectX implements comprehensive cryptographic verification at both the transport layer (NetworkContainer) and message layer (NetworkEvent). This two-layer verification system ensures message authenticity, prevents spoofing attacks, and enables secure relay in P2P mesh networks.

**Critical Security Requirement:** All network communications MUST be cryptographically signed and verified. No unverified deserialization is permitted.

### NetworkContainer Signature Verification

**Location:** `NodeMesh.processNetworkInput()` (lines 118-215)

Every incoming NetworkContainer undergoes mandatory signature verification before processing:

#### Verification Flow

```java
// Step 1: Read signed NetworkContainer bytes from socket
ByteArrayOutputStream signedContainerBytes = new ByteArrayOutputStream();
byte[] buffer = new byte[8192];
int bytesRead;
while ((bytesRead = is.read(buffer)) != -1) {
    signedContainerBytes.write(buffer, 0, bytesRead);
}

// Step 2: Strip signature to peek at transmitter ID (NOT YET VERIFIED)
ByteArrayInputStream peekStream = new ByteArrayInputStream(signedContainerBytes.toByteArray());
ByteArrayOutputStream peekBaos = new ByteArrayOutputStream();
connectX.encryptionProvider.stripSignature(peekStream, peekBaos);
String unverifiedContainerJson = peekBaos.toString("UTF-8");

// Step 3: Deserialize UNVERIFIED container to extract transmitter ID
NetworkContainer unverifiedContainer =
    (NetworkContainer) ConnectX.deserialize("cxJSON1", unverifiedContainerJson, NetworkContainer.class);

if (unverifiedContainer.iD == null) {
    Analytics.addData(AnalyticData.Tear, "NetworkContainer missing transmitter ID");
    throw new DecryptionFailureException();
}

// Step 4: Check if we have the transmitter's public key
boolean hasPublicKey = connectX.encryptionProvider.cacheCert(unverifiedContainer.iD, false, false);

if (!hasPublicKey) {
    // UNKNOWN SENDER - Only CXHELLO and NewNode allowed
    // Peek at event type to validate this is a bootstrap message
    ByteArrayInputStream eventPeekStream = new ByteArrayInputStream(unverifiedContainer.e);
    ByteArrayOutputStream eventPeekBaos = new ByteArrayOutputStream();
    connectX.encryptionProvider.stripSignature(eventPeekStream, eventPeekBaos);
    String eventJson = eventPeekBaos.toString("UTF-8");
    NetworkEvent peekedEvent =
        (NetworkEvent) ConnectX.deserialize(unverifiedContainer.se, eventJson, NetworkEvent.class);

    if (peekedEvent.eT == null ||
        !(peekedEvent.eT.equals("NewNode") || peekedEvent.eT.equals("CXHELLO"))) {
        // NOT a bootstrap message - REJECT
        Analytics.addData(AnalyticData.Tear,
            "Message from unknown sender " + unverifiedContainer.iD +
            " (event: " + peekedEvent.eT + ") - only CXHELLO/NewNode allowed");
        throw new DecryptionFailureException();
    }

    // This is CXHELLO or NewNode - key will be imported below
    nc = unverifiedContainer; // Use unverified (will verify after key import)

} else {
    // Step 5: We have the public key - VERIFY NetworkContainer signature
    ByteArrayInputStream verifyStream = new ByteArrayInputStream(signedContainerBytes.toByteArray());
    ByteArrayOutputStream verifiedBaos = new ByteArrayOutputStream();
    boolean verified = connectX.encryptionProvider.verifyAndStrip(
        verifyStream, verifiedBaos, unverifiedContainer.iD);

    if (!verified) {
        // Signature verification FAILED - transmitter ID is SPOOFED
        Analytics.addData(AnalyticData.Tear,
            "NetworkContainer signature verification FAILED for " + unverifiedContainer.iD);
        throw new DecryptionFailureException();
    }

    // Signature VERIFIED - we can now trust nc.iD and all container fields
    String verifiedContainerJson = verifiedBaos.toString("UTF-8");
    nc = (NetworkContainer) ConnectX.deserialize("cxJSON1", verifiedContainerJson, NetworkContainer.class);

    System.out.println("[SECURITY] ✓ NetworkContainer signature VERIFIED for " +
                      nc.iD.substring(0, 8));
}
```

#### Security Modes

**1. Known Sender Mode (Full Verification)**
- Sender's public key is cached
- NetworkContainer signature verified using `verifyAndStrip()`
- All container fields trusted after verification
- Rejection on verification failure

**2. Unknown Sender Mode (Bootstrap Only)**
- Sender's public key not yet known
- Only CXHELLO and NewNode events permitted
- Non-bootstrap messages from unknown senders REJECTED
- Public key imported from event payload
- Signature verified AFTER key import

#### Bootstrap Event Handling

**NewNode/CXHELLO Special Processing** (lines 246-320):

```java
// Import node BEFORE verification (we need the public key)
if (parsedEvent.eT.equals("NewNode") || parsedEvent.eT.equals("CXHELLO")) {
    // Extract Node from payload
    String payloadJson = new String(parsedEvent.d, "UTF-8");
    Node newNode = (Node) ConnectX.deserialize("cxJSON1", payloadJson, Node.class);

    // SECURITY: Validate node data
    if (newNode.cxID == null || newNode.publicKey == null) {
        throw new DecryptionFailureException();
    }

    // SECURITY: Verify transmitter matches the node being added
    if (!newNode.cxID.equals(nc.iD)) {
        throw new DecryptionFailureException();
    }

    // Import the node (adds public key to cache)
    PeerDirectory.addNode(newNode);

    // NOW VERIFY the signature using the imported public key
    ByteArrayInputStream verifyBais = new ByteArrayInputStream(nc.e);
    ByteArrayOutputStream verifyBaos = new ByteArrayOutputStream();
    Object verified = connectX.encryptionProvider.verifyAndStrip(verifyBais, verifyBaos, nc.iD);

    if (verified == null) {
        // Signature verification FAILED - rollback
        PeerDirectory.removeNode(newNode.cxID);
        throw new DecryptionFailureException();
    }

    System.out.println("[NodeMesh] NewNode signature VERIFIED for " + newNode.cxID);
}
```

**Rollback on Failure:**
- If verification fails after key import, the node is removed from PeerDirectory
- Prevents poisoning the peer cache with invalid nodes
- Ensures atomic import-and-verify operation

### Signed Blob Preservation for Relay

**Problem:** In P2P networks, nodes relay messages. If each relay re-signs the content, the original sender's signature is lost.

**Solution:** Preserve original signed blobs through the relay chain.

#### EventBuilder.signData() Method

**Location:** `ConnectX.java` (lines 647-668)

```java
/**
 * NOTE: Builder is accessed ConnectX.buildEvent()
 * Sign the event data payload
 * Replaces event.d with a signed blob, preserving the original signature for relay
 * Used for NewNode events where the Node blob must be signed by the sender
 * @return This builder for chaining
 */
public EventBuilder signData() {
    try {
        // Sign the current data payload
        ByteArrayInputStream dataInput = new ByteArrayInputStream(this.event.d);
        ByteArrayOutputStream signedOutput = new ByteArrayOutputStream();
        connectX.encryptionProvider.sign(dataInput, signedOutput);

        // Replace event data with signed blob
        this.event.d = signedOutput.toByteArray();

        return this;
    } catch (Exception e) {
        throw new RuntimeException("Failed to sign event data", e);
    }
}
```

#### Usage: NewNode Event Creation

**Location 1:** `ConnectX.initiateP2PDiscovery()` (lines 1117-1123)

```java
// Send NewNode with SIGNED Node blob (receiver will save original signed blob for relay)
System.out.println("[P2P Discovery] Sending NewNode to " + peer.cxID.substring(0, 8));
String selfJson = serialize("cxJSON1", self);
buildEvent(EventType.NewNode, selfJson.getBytes("UTF-8"))
    .signData()  // Sign Node JSON to create signed blob (preserves sender signature)
    .toPeer(peer.cxID)
    .queue();
```

**Location 2:** `NodeMesh.fireEvent() PeerFinding Handler` (lines 852-857)

```java
// Send NewNode with SIGNED Node blob (receiver will save original signed blob for relay)
String selfJson = ConnectX.serialize("cxJSON1", connectX.getSelf());
connectX.buildEvent(EventType.NewNode, selfJson.getBytes("UTF-8"))
    .signData()  // Sign Node JSON to create signed blob (preserves sender signature)
    .toPeer(discoveredPeer.cxID)
    .queue();
```

#### Signed Blob Storage

**PeerDirectory.addNode(Node, byte[])** (lines 127-160):

```java
public static void addNode(Node n, byte[] signed) {
    if (Node.validate(n)) {
        // Add to in-memory directories (same as regular addNode)
        addNode(n);

        // Cache the signed blob for relaying
        if (signed != null) {
            signedNodeCache.put(n.cxID, signed);

            // Persist to disk (nodemesh/{first_char}/{cxID}.cxi)
            char firstChar = n.cxID.charAt(0);
            File peerGroup = new File(peers, String.valueOf(firstChar));
            if (!peerGroup.exists()) {
                peerGroup.mkdirs();
            }

            File peerFile = new File(peerGroup, n.cxID + ".cxi");
            FileOutputStream fos = new FileOutputStream(peerFile);
            fos.write(signed);  // Write SIGNED blob, not JSON
            fos.flush();
            fos.close();

            System.out.println("[PeerDirectory] Persisted signed node: " +
                             n.cxID.substring(0, 8) + " (" + signed.length + " bytes)");
        }
    }
}
```

**Retrieval:** `PeerDirectory.getSignedNode(String cxID)`

Returns the original signed blob from memory cache or disk, enabling relay without re-signing.

#### NewNode Receiver Implementation

**Location:** `NodeMesh.processEvent()` (lines 516-546)

```java
case NewNode:
    // NewNode events are now sent with SIGNED Node blobs (via .signData())
    byte[] signedNodeBlob = ib.ne.d;

    // Verify and deserialize the signed Node blob
    Node node = (Node) connectX.getSignedObject(
        null, // Will extract cxID from signature
        new ByteArrayInputStream(signedNodeBlob),
        Node.class,
        "cxJSON1"
    );

    if (node == null || node.cxID == null) {
        System.err.println("[NodeMesh] NewNode verification failed - invalid signature");
        return;
    }

    // Check if we already have this node
    Node node1 = PeerDirectory.lookup(node.cxID, true, true, connectX.cxRoot, connectX);
    if (node1 != null) {
        connectX.encryptionProvider.cacheCert(node1.cxID, true, false);
        return;
    }

    // Add node WITH signed blob (preserves original signature for relay)
    PeerDirectory.addNode(node, signedNodeBlob);
    System.out.println("[NodeMesh] Imported NewNode: " + node.cxID.substring(0, 8));
    System.out.println("[NodeMesh] NewNode signature VERIFIED and SAVED for relay");
    break;
```

### PeerFinding Relay Mechanism

**Purpose:** Distribute signed Node blobs through the network without re-signing.

**PeerFinding Event Structure** (from `PeerFinding.java`):

```java
public class PeerFinding {
    public byte[] currentPeers;      // 30% of current connections (up to 5)
    public byte[] peers;             // IP/Socket/Bridges (30% max 20)
    public String network;           // Network identifier (optional)
    public List<byte[]> signedNodes; // Up to 50 signed Node blobs
    public String t;                 // "request" or "response"
}
```

**Response Handler** (NodeMesh.fireEvent, lines 764-787):

```java
// Build response with signed node blobs (up to 50 random peers)
PeerFinding response = new PeerFinding();
response.t = "response";
response.network = requestedNetwork;
response.signedNodes = new ArrayList<>();

// Collect peers from PeerDirectory
List<String> peerIDs = new ArrayList<>(PeerDirectory.hv.keySet());
Collections.shuffle(peerIDs); // Randomize

int count = 0;
for (String peerID : peerIDs) {
    if (count >= 50) break; // Limit to 50
    if (peerID.equals(nc.iD) || peerID.equals(connectX.getOwnID())) continue;

    // Get signed node blob (original signature preserved)
    byte[] signedNode = PeerDirectory.getSignedNode(peerID);
    if (signedNode != null) {
        response.signedNodes.add(signedNode);
        count++;
    }
}

System.out.println("[PeerFinding] Responding with " + count + " signed peers");
```

**Import Handler** (NodeMesh.fireEvent, lines 821-863):

```java
// Handle PeerFinding response - import discovered peers
for (Object signedNodeObj : pf.signedNodes) {
    byte[] signedNodeBytes = (byte[]) signedNodeObj;

    // Verify and import signed node
    Node discoveredPeer = (Node) connectX.getSignedObject(
        null, // Will extract cxID from signature
        new ByteArrayInputStream(signedNodeBytes),
        Node.class,
        "cxJSON1"
    );

    if (discoveredPeer != null && discoveredPeer.cxID != null) {
        // Add with signed blob for persistence and relaying
        PeerDirectory.addNode(discoveredPeer, signedNodeBytes);
        System.out.println("[PeerFinding]   + " + discoveredPeer.cxID.substring(0, 8));

        // Send NewNode to establish crypto with newly discovered peer
        String selfJson = ConnectX.serialize("cxJSON1", connectX.getSelf());
        connectX.buildEvent(EventType.NewNode, selfJson.getBytes("UTF-8"))
            .signData()  // Sign our Node for relay
            .toPeer(discoveredPeer.cxID)
            .queue();
    }
}
```

### Security Benefits

**1. Anti-Spoofing Protection**
- Transmitter ID (nc.iD) cryptographically verified
- Cannot impersonate another peer
- Rollback mechanism prevents poisoning peer cache

**2. Message Authenticity**
- Every NetworkContainer signed by transmitter
- Every NetworkEvent signed by original sender
- Two-layer verification ensures both transport and message integrity

**3. Relay Chain Trust**
- Signed blobs preserve original sender's signature
- Recipients can verify the ORIGINAL sender, not just the relay
- Prevents man-in-the-middle modifications

**4. Bootstrap Security**
- Unknown senders restricted to CXHELLO and NewNode only
- Public key imported atomically with signature verification
- Verification failure triggers immediate rollback

**5. Audit Trail**
- All verification failures logged to Analytics
- Signature verification success logged for monitoring
- Can detect and track spoofing attempts

### Wire Format

**Complete Message Structure:**

```
[Signed NetworkContainer]
  └─> NetworkContainer JSON (signed by transmitter)
      ├─> iD: transmitter UUID
      ├─> e: [Signed NetworkEvent]
      │   └─> NetworkEvent JSON (signed by original sender)
      │       ├─> eT: event type
      │       ├─> d: [Signed Data] (for NewNode: signed Node blob)
      │       │   └─> Node JSON (signed by node owner)
      │       ├─> iD: event UUID
      │       └─> p: routing path
      ├─> se: serialization method
      └─> tP: transmission preferences
```

**Three Signature Layers:**
1. NetworkContainer signature (transport authentication)
2. NetworkEvent signature (message authentication)
3. Event data signature (payload authentication - for NewNode)

### Testing & Validation

**MultiPeerTest Results (2025-12-29):**

```
✓ NetworkContainer signature VERIFIED for c622416b
✓ NewNode signature VERIFIED for c622416b-a195-4a57-83e1-f26a07cab0bd
✓ Signed blob SAVED for relay
✓ 4 peers discovered via LAN with crypto verification
✓ All peers reached READY state in 18 seconds
✓ Zero signature verification failures
✓ Zero spoofing attempts detected
```

**Test Coverage:**
- ✅ Known sender verification (standard messages)
- ✅ Unknown sender rejection (non-bootstrap messages)
- ✅ Bootstrap event verification (CXHELLO/NewNode)
- ✅ Signed blob preservation and relay
- ✅ Rollback on verification failure
- ✅ P2P discovery with crypto authentication

### Error Handling

**Common Failures:**

1. **Missing transmitter ID** → DecryptionFailureException
   - Log: "NetworkContainer missing transmitter ID"
   - Action: Drop message, close socket

2. **Unknown sender sending non-bootstrap message** → DecryptionFailureException
   - Log: "Message from unknown sender {id} (event: {type}) - only CXHELLO/NewNode allowed"
   - Action: Drop message, record analytics

3. **Signature verification failure** → DecryptionFailureException
   - Log: "NetworkContainer signature verification FAILED for {id}"
   - Action: Drop message, record analytics, potential spoofing attempt

4. **NewNode verification failure after key import** → DecryptionFailureException
   - Log: "NewNode signature verification FAILED for {id}"
   - Action: Rollback (remove imported node), drop message

5. **Transmitter/node ID mismatch** → DecryptionFailureException
   - Log: "NewNode cxID mismatch: {nodeID} vs {transmitterID}"
   - Action: Drop message, potential spoofing attempt

**All failures trigger:**
- Socket closure (if applicable)
- Analytics recording (AnalyticData.Tear)
- Security logging for audit trail

### Implementation Checklist

When implementing crypto verification in other protocols:

- ☑ NetworkContainer signature verification BEFORE deserialization
- ☑ Transmitter ID (nc.iD) validation
- ☑ Public key caching mechanism
- ☑ Unknown sender restriction (bootstrap only)
- ☑ Two-phase verification for bootstrap events
- ☑ Rollback mechanism on verification failure
- ☑ Signed blob preservation for relay
- ☑ Event data signing (.signData() method)
- ☑ Security logging and analytics
- ☑ Comprehensive error handling

---

## End-to-End (E2E) Encryption

**Implementation Date:** 2025-12-30
**Version:** 3.0.2
**Status:** Production Ready

### Overview

ConnectX supports optional **End-to-End (E2E) encryption** for events that require recipient-specific confidentiality. E2E encryption uses PGP multi-recipient encryption to ensure only designated recipients can decrypt event data, even as the event travels through relay nodes.

**Key Features:**
- **Multi-recipient PGP encryption** using PGPainless library
- **Optional encryption** - Messages can be sent encrypted or unencrypted
- **Sender auto-inclusion** - Sender is automatically added as a recipient
- **Relay transparency** - Relay nodes cannot decrypt E2E events
- **Access control** - Non-recipients receive DecryptionFailureException

### E2E Encryption Flag

The E2E flag is located in the NetworkEvent structure:

```java
NetworkEvent {
    String eT;          // Event type
    byte[] d;           // Event data (encrypted if e2e=true, signed if e2e=false)
    CXPath p;           // Routing path
    String iD;          // Event ID
    boolean e2e = false;  // E2E encryption flag
}
```

**Behavior:**
- `e2e = false` (default): NetworkEvent.d contains **signed** event data (normal flow)
- `e2e = true`: NetworkEvent.d contains **PGP-encrypted** event data (E2E mode)

### API Usage

#### Building E2E Encrypted Events

```java
// Example: Send E2E encrypted message to specific recipients
connectX.buildEvent(EventType.MESSAGE, "Secret data".getBytes())
    .addRecipient(peer1ID)     // Add recipient 1
    .addRecipient(peer2ID)     // Add recipient 2
    .addRecipient(peer3ID)     // Add recipient 3
    .encrypt()                 // Enable E2E encryption
    .toNetwork("TESTNET")      // Target network
    .queue();                  // Queue for transmission
```

**Method Chain:**
1. `.addRecipient(String nodeID)` - Add a recipient by node UUID
2. `.encrypt()` - Encrypts data for all recipients + sender
3. `.toNetwork()` / `.toNode()` - Set routing
4. `.queue()` - Queue event for transmission

#### EventBuilder Implementation

**Location:** `ConnectX.EventBuilder` (ConnectX.java)

```java
public class EventBuilder {
    private List<String> encryptionRecipients = new ArrayList<>();

    // Add recipient for E2E encryption
    public EventBuilder addRecipient(String recipientNodeID) {
        encryptionRecipients.add(recipientNodeID);
        return this;
    }

    // Enable E2E encryption
    public EventBuilder encrypt() throws Exception {
        // Auto-add sender as recipient
        encryptionRecipients.add(connectXAPI.getOwnID());

        // Encrypt data for all recipients
        byte[] encryptedData = connectXAPI.encryptionProvider
            .encrypt(data, encryptionRecipients);

        // Update event data with encrypted version
        networkEvent.d = encryptedData;

        // Set E2E flag
        networkEvent.e2e = true;

        return this;
    }
}
```

**Auto-Inclusion:** Sender is automatically added as a recipient so they can decrypt their own sent messages.

### Multi-Recipient Encryption

**Location:** `PainlessCryptProvider.encrypt()` (PainlessCryptProvider.java)

```java
@Override
public byte[] encrypt(byte[] data, List<String> recipientIDs) throws Exception {
    // Build list of recipient PGP public keys
    List<PGPPublicKeyRing> recipientKeys = new ArrayList<>();

    for (String recipientID : recipientIDs) {
        PGPPublicKeyRing publicKey = getPublicKeyForNode(recipientID);
        recipientKeys.add(publicKey);
    }

    // PGP encrypt for multiple recipients
    ByteArrayOutputStream encryptedOutputStream = new ByteArrayOutputStream();

    EncryptionStream encryptionStream = PGPainless.encryptAndOrSign()
        .onOutputStream(encryptedOutputStream)
        .withOptions(ProducerOptions.encrypt(
            EncryptionOptions.encryptCommunications()
                .addRecipients(recipientKeys)
        ));

    encryptionStream.write(data);
    encryptionStream.close();

    return encryptedOutputStream.toByteArray();
}
```

**PGP Multi-Recipient:** Each recipient's public key is used to encrypt a copy of the session key. Any recipient can decrypt using their private key.

### E2E Decryption

**Location:** `NodeMesh.processEvent()` (NodeMesh.java:489-521)

When processing events, NodeMesh checks the E2E flag:

```java
// Check if event is E2E encrypted
byte[] eventData;  // Separate variable for decrypted data

if (ib.ne.e2e) {
    // E2E encrypted - attempt decryption
    System.out.println("[E2E] Decrypting E2E encrypted event");
    try {
        eventData = connectXAPI.encryptionProvider.decrypt(ib.ne.d);
        System.out.println("[E2E] Successfully decrypted E2E event data");
    } catch (DecryptionFailureException e) {
        // Not a recipient - cannot decrypt
        System.out.println("[E2E] Cannot decrypt - not in recipient list");
        // Could relay if needed, but cannot process
        return;
    }
} else {
    // Normal signed event - verify and deserialize
    connectXAPI.encryptionProvider.verifyAndStrip(
        ib.ne.d, eventDataBuffer, ib.ne.p.se
    );
    eventData = eventDataBuffer.toByteArray();
}

// Use eventData for further processing
// Original ib.ne.d is preserved for downstream crypto operations
```

**Important:** Uses separate `eventData` variable to preserve original `ib.ne.d` for potential downstream signature operations or relay.

### Security Properties

#### Confidentiality
- **Recipients Only:** Only designated recipients (+ sender) can decrypt
- **Relay Transparency:** Relay nodes see encrypted bytes, cannot read content
- **Non-Recipient Rejection:** Non-recipients get DecryptionFailureException

#### Integrity
- **PGP Encryption:** Includes integrity protection (MDC - Modification Detection Code)
- **Tamper Detection:** Any modification to ciphertext fails decryption
- **Authenticity:** Combined with NetworkEvent signature for sender authentication

#### Access Control
```
Scenario: Peer1 sends E2E message to Peer2 & Peer3

Peer1 (sender):        ✓ Can decrypt (auto-added as recipient)
Peer2 (recipient):     ✓ Can decrypt (explicitly added)
Peer3 (recipient):     ✓ Can decrypt (explicitly added)
Peer4 (relay):         ✗ Cannot decrypt (not in recipient list)
Peer5 (eavesdropper):  ✗ Cannot decrypt (not in recipient list)
```

### Logging

E2E operations generate specific log tags:

```
[E2E] Encrypted event data for N recipients     - Encryption successful
[E2E] Decrypting E2E encrypted event            - Attempting decryption
[E2E] Successfully decrypted E2E event data     - Decryption successful
[E2E] Cannot decrypt - not in recipient list    - DecryptionFailureException
```

### Use Cases

**Appropriate for E2E:**
- Private messages between specific users
- Confidential transactions or contracts
- Sensitive data requiring recipient-specific access
- Multi-party encrypted communications

**Not needed for E2E:**
- Public broadcasts to entire network
- Network administration events (already signed)
- Resource distribution (public data)
- Blockchain events (transparency required)

### Configuration

E2E encryption is controlled at the application level:

**NodeConfig.java:**
```java
// No global E2E flag - encryption is per-event via .encrypt()
```

**Per-Network Configuration (future):**
```java
Configuration {
    Boolean requireE2E = false;  // Future: Mandate E2E for all events
}
```

Currently, E2E is **optional** and controlled via the `.encrypt()` method in EventBuilder.

### Testing & Validation

**Test Suite:** `MultiPeerTest.runE2EEncryptionTest()` (MultiPeerTest.java:1137-1248)

**Test Date:** 2025-12-30
**Status:** ✓ All tests passing

#### Test Cases

**TEST 1: Baseline Non-Encrypted Message**
```java
peers.get(0).buildEvent(EventType.MESSAGE, "Regular message - NOT encrypted".getBytes())
    .toNetwork("TESTNET")
    .queue();
```
- ✓ Verifies normal (non-E2E) message flow for comparison
- ✓ No [E2E] log tags appear

**TEST 2: Multi-Recipient E2E Encryption (2 recipients)**
```java
peers.get(0).buildEvent(EventType.MESSAGE, "SECRET: E2E encrypted message!".getBytes())
    .addRecipient(peers.get(1).getOwnID())  // Peer 2
    .addRecipient(peers.get(2).getOwnID())  // Peer 3
    .encrypt()
    .toNetwork("TESTNET")
    .queue();
```
- ✓ Peer 1 (sender) can decrypt (auto-included)
- ✓ Peer 2 can decrypt (recipient)
- ✓ Peer 3 can decrypt (recipient)
- ✓ Peer 4 cannot decrypt (not in recipient list)
- ✓ Logs: `[E2E] Encrypted event data for 2 recipients`

**TEST 3: Second Multi-Recipient Message (different sender/recipients)**
```java
peers.get(1).buildEvent(EventType.MESSAGE, "SECRET: Another E2E message from Peer 2!".getBytes())
    .addRecipient(peers.get(2).getOwnID())  // Peer 3
    .addRecipient(peers.get(3).getOwnID())  // Peer 4
    .encrypt()
    .toNetwork("TESTNET")
    .queue();
```
- ✓ Verifies E2E works with different senders
- ✓ Peer 1 cannot decrypt (not in recipient list)
- ✓ Peer 2, 3, 4 can decrypt

**TEST 4: Single-Recipient E2E Encryption**
```java
peers.get(2).buildEvent(EventType.MESSAGE, "SECRET: Private message to Peer 1 only!".getBytes())
    .addRecipient(peers.get(0).getOwnID())  // Only Peer 1
    .encrypt()
    .toNetwork("TESTNET")
    .queue();
```
- ✓ Peer 3 (sender) can decrypt
- ✓ Peer 1 can decrypt (sole recipient)
- ✓ Peers 2, 4 cannot decrypt
- ✓ Logs: `[E2E] Encrypted event data for 1 recipients`

#### Test Results

**Observed Log Output:**
```
[E2E] Encrypted event data for 2 recipients     ✓ Multi-recipient encryption
[E2E] Encrypted event data for 1 recipients     ✓ Single-recipient encryption
[E2E] Decrypting E2E encrypted event            ✓ Decryption attempts
[E2E] Successfully decrypted E2E event data     ✓ Recipient decryption success
[E2E] Failed to decrypt E2E event: null         ✓ Non-recipient rejection
```

**Validation:**
- ✓ PGP multi-recipient encryption working
- ✓ Sender auto-inclusion functioning
- ✓ Access control enforced (non-recipients rejected)
- ✓ Event routing unaffected by E2E flag
- ✓ Relay nodes forward encrypted events correctly

**Test Environment:**
- 4 peers bootstrapped into TESTNET
- P2P mesh networking via LAN discovery
- No EPOCH/NMI required for E2E functionality

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

## Blockchain Consensus

### Overview

ConnectX uses a **hybrid consensus model** that supports both centralized (NMI-backed) and fully decentralized (zero-trust) operation modes. The consensus mechanism leverages cryptographic signatures on events to ensure integrity and enables true peer-to-peer operation without central authority.

**Key Principles:**
- Events are chronologically ordered by timestamp + signature
- All events stored as signed blobs (preserves cryptographic integrity)
- Backends provide routing priority only (not special consensus authority)
- Network can operate fully P2P without NMI/backends
- Dual-mode operation: NMI-backed (fast) or Zero-Trust (decentralized)

### Signed Event Blobs

Every event in a block is stored as its original cryptographically-signed bytes:

```java
NetworkBlock {
    Long block;      // Block number
    Long chain;      // Chain ID (c1, c2, or c3)

    // PERSISTED: Original signed bytes (includes signature)
    // Key: Event index, Value: Signed blob (NetworkEvent serialized + signature)
    Map<Integer, byte[]> networkEvents;

    // MEMORY ONLY: Deserialized for processing (NOT persisted to disk)
    @MemoryOnly
    Map<Integer, NetworkEvent> deserializedEvents;
}
```

**Why Signed Blobs:**
- Preserves cryptographic proof of authorship
- Enables signature verification at any time
- Required for consensus validation
- Supports block reconciliation
- Detects Byzantine behavior (double-signing)
- **CRITICAL:** Same blob used for blockchain AND network transmission (consistency guarantee)

**Lifecycle:**
```
1. Event created locally → Serialize → Sign → Get signed blob
2. Record to blockchain → Use signed blob (Event method)
3. Transmit to peers → Use SAME signed blob (no re-signing)
4. Event received → Signature verified at fireEvent()
5. Block persisted → networkEvents (signed blobs) saved to disk
6. Block loaded → networkEvents loaded from disk
7. Event needed → prepare() → Verify signature → Deserialize → Cache in deserializedEvents
```

**Implementation:**

```java
// Recording at transmission time (OutConnectionController.transmitEvent)
if (out.prev == null && scope.equals("CXN") && chainID != null) {
    // Sign event for transmission
    byte[] signedBlob = signObject(event);

    // Record to blockchain using the EXACT same signed blob
    connectX.Event(event, senderID, signedBlob);

    // Transmit using the SAME signed blob
    nc.e = signedBlob;
    transmit(nc);
}

// Receiving events (NodeMesh.fireEvent)
public boolean fireEvent(NetworkEvent ne, NetworkContainer nc, byte[] signedEventBytes) {
    // CRITICAL: Verify signature BEFORE processing
    if (signedEventBytes != null) {
        if (!verifySignature(signedEventBytes, ne.p.cxID)) {
            return false; // REJECT forged events
        }
    }

    // Process event...

    // Record to blockchain if needed
    connectX.Event(ne, ne.p.cxID, nc.e); // Use original sender's signature
}

// Accessing events from blockchain
NetworkBlock block = chain.getBlock(blockID);
block.prepare(connectX);  // Verify and deserialize all events

for (NetworkEvent event : block.deserializedEvents.values()) {
    // Process verified event
}
```

**Security Guarantees:**
- ✅ Events are signed exactly once by original sender
- ✅ Same blob in blockchain and network transmission
- ✅ Signatures verified before processing (fireEvent entry point)
- ✅ Cannot forge events (signature verification required)
- ✅ Cannot modify events after signing (breaks signature)
- ✅ Blockchain contains cryptographically-verifiable history

### Consensus Modes

#### Mode 1: NMI-Backed Consensus (Default)

**Configuration:**
```java
CXNetwork {
    boolean zT = false;  // NMI has authority
}
```

**Behavior:**
- NMI is authoritative on blockchain conflicts
- Faster conflict resolution
- Suitable for centralized or semi-centralized networks
- Backend nodes have Record permissions but NMI arbitrates disputes

**Block Acceptance:**
```
1. Event received with valid signature
2. Check sender has Record-{chainID} permission
3. Verify signature using encryptionProvider
4. Store signed blob in block
5. On conflict: Query NMI for canonical block
```

**Conflict Resolution:**
```java
if (blockConflict && !zT && nmiReachable()) {
    // Ask NMI which block is canonical
    Block canonicalBlock = queryNMI(conflictingBlocks);
    acceptBlock(canonicalBlock);
}
```

#### Mode 2: Zero-Trust Consensus (Fully Decentralized)

**Activation (IRREVERSIBLE):**
```java
// Only NMI can initiate (one-time, permanent transition)
connectX.startZeroTrust("TESTNET");

// What happens:
1. NMI creates ZERO_TRUST_ACTIVATION event
2. Network seed updated with zT=true
3. Event distributed CXN, recorded to c1
4. All peers switch to zT mode automatically
5. NMI permissions BLOCKED forever (kept for historical validation)
```

**Configuration:**
```java
CXNetwork {
    boolean zT = true;  // Zero-trust mode active
}
```

**Behavior:**
- Pure peer-to-peer consensus
- NMI permissions blocked (cannot perform any actions)
- Multi-peer validation for block acceptance
- Slower but fully decentralized
- Works completely offline

**Block Acceptance:**
```
1. Event received with valid signature
2. Check sender has Record-{chainID} permission (NMI blocked in zT)
3. Verify signature
4. Store signed blob
5. Collect peer votes on this block
6. Accept if quorum threshold reached (51%+ of weighted votes)
```

**Peer Consensus Algorithm:**
```java
// Multi-peer block validation in zT mode
private NetworkBlock peerConsensus(Long blockHeight, Long chainID) {
    // 1. Query multiple peers for their block version
    Map<String, NetworkBlock> peerBlocks = new HashMap<>();

    for (String peerID : getPeersWithRecordPermission(chainID)) {
        NetworkBlock block = queryPeerForBlock(peerID, blockHeight, chainID);
        if (block != null) {
            peerBlocks.put(peerID, block);
        }
    }

    // 2. Group identical blocks
    Map<NetworkBlock, List<String>> blockVotes = new HashMap<>();
    for (Map.Entry<String, NetworkBlock> entry : peerBlocks.entrySet()) {
        blockVotes.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                  .add(entry.getKey());
    }

    // 3. Weight-based voting (use permission weights)
    Map<NetworkBlock, Integer> voteWeights = new HashMap<>();
    for (Map.Entry<NetworkBlock, List<String>> entry : blockVotes.entrySet()) {
        int totalWeight = 0;
        for (String voterID : entry.getValue()) {
            Integer weight = network.getVariableNetworkPermission(voterID, "Record");
            if (weight != null) {
                totalWeight += weight;
            }
        }
        voteWeights.put(entry.getKey(), totalWeight);
    }

    // 4. Block with highest cumulative weight wins
    return voteWeights.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey())
        .orElse(null);
}
```

### Block Reconciliation

When peers have different blocks at the same height, reconciliation merges events using signed blobs:

```java
private NetworkBlock reconcileBlocks(List<NetworkBlock> conflictingBlocks) {
    System.out.println("[CONSENSUS] Reconciling " + conflictingBlocks.size() + " blocks");

    // 1. Extract all signed event blobs
    Map<String, byte[]> allSignedBlobs = new HashMap<>();
    for (NetworkBlock block : conflictingBlocks) {
        for (Map.Entry<Integer, byte[]> entry : block.signedEventBlobs.entrySet()) {
            // Deserialize to get event ID
            NetworkEvent event = deserializeAndVerify(entry.getValue());
            if (event != null) {
                allSignedBlobs.putIfAbsent(event.iD, entry.getValue());
            }
        }
    }

    // 2. Verify all signatures
    Map<String, byte[]> validBlobs = new HashMap<>();
    for (Map.Entry<String, byte[]> entry : allSignedBlobs.entrySet()) {
        NetworkEvent event = deserializeAndVerify(entry.getValue());
        if (event != null && verifyEventSignature(entry.getValue(), event)) {
            validBlobs.put(entry.getKey(), entry.getValue());
        }
    }

    // 3. Sort chronologically
    List<Map.Entry<String, byte[]>> sortedEvents = new ArrayList<>(validBlobs.entrySet());
    sortedEvents.sort((e1, e2) -> {
        NetworkEvent ev1 = deserialize(e1.getValue());
        NetworkEvent ev2 = deserialize(e2.getValue());

        // Compare timestamps
        int timeCompare = Long.compare(ev1.timestamp, ev2.timestamp);
        if (timeCompare != 0) return timeCompare;

        // Tie-break: deterministic by event ID
        return ev1.iD.compareTo(ev2.iD);
    });

    // 4. Build canonical block with merged events
    NetworkBlock canonical = new NetworkBlock(blockHeight, chainID);
    int index = 0;
    for (Map.Entry<String, byte[]> entry : sortedEvents) {
        canonical.signedEventBlobs.put(index++, entry.getValue());
    }

    System.out.println("[CONSENSUS] Canonical block created with " + index + " events");
    return canonical;
}
```

### Multi-Peer Block Querying

**BLOCK_REQUEST_MULTI Event:**
```json
{
    "network": "TESTNET",
    "chain": 3,
    "block": 42,
    "requestID": "uuid-xxx"
}
```

**Sent to:** Multiple peers with Record permission (not just one)

**BLOCK_RESPONSE Payload:**
```json
["dev.droppinganvil.v3.edge.NetworkBlock", {
    "block": 42,
    "chain": 3,
    "signedEventBlobs": {
        "0": [base64-encoded-signed-blob],
        "1": [base64-encoded-signed-blob],
        "2": [base64-encoded-signed-blob]
    }
}]
```

**CRITICAL:** Always transmit `signedEventBlobs`, never `networkEvents`

### Byzantine Fault Detection

**Double-Signing Detection:**
```java
// Detect if same node signed two different blocks at same height
if (block1.height == block2.height &&
    block1.authorID.equals(block2.authorID) &&
    !Arrays.equals(block1.signature, block2.signature)) {

    System.out.println("[BYZANTINE] Double-signing detected from " + block1.authorID);

    // In zT mode: Reduce trust weight for this node
    if (network.zT) {
        penalizeNode(block1.authorID);
    }

    // In NMI mode: Report to NMI for adjudication
    else {
        reportByzantineNode(block1.authorID, block1, block2);
    }
}
```

### Zero-Trust Activation

**ZeroTrustEvent Structure:**
```java
EventType.ZERO_TRUST_ACTIVATION

Payload:
{
    "action": "activate_zero_trust",
    "networkID": "TESTNET",
    "timestamp": 1735550000,
    "newSeed": {
        "networkID": "TESTNET",
        "zT": true,
        "configuration": {...},
        "nmiPub": "..." // Preserved for historical validation
    }
}
```

**Activation Process:**
```
1. NMI calls .startZeroTrust(networkID)
2. System creates updated seed with zT=true
3. ZERO_TRUST_ACTIVATION event distributed (CXN scope, recorded to c1)
4. All peers receive event:
   - Apply new seed
   - Set network.zT = true
   - Block NMI permissions (checkNetworkPermission returns false for NMI)
   - Begin using peer consensus
5. Chains resynced using zero-trust protocols
```

**Post-Activation:**
```java
// NMI permissions blocked
public boolean checkNetworkPermission(String deviceID, String permission) {
    if (zT && deviceID.equals(networkDictionary.nmi)) {
        System.out.println("[ZT-MODE] NMI permission blocked - network is zero-trust");
        return false;  // NMI cannot perform actions in zT mode
    }
    return networkPermissions.allowed(deviceID, permission);
}

// Historical validation still works (for blockchain replay)
// NMI permissions remain in map for chronological validation
```

### Use Cases

**NMI-Backed Consensus:**
- Corporate/enterprise networks
- Networks requiring quick dispute resolution
- Semi-centralized governance models
- Networks in active development/fine-tuning

**Zero-Trust Consensus:**
- Fully decentralized communities
- Networks that have stabilized
- Byzantine-fault-tolerant requirements
- Maximum censorship resistance

**Transition Pattern:**
```
1. Create network (NMI-backed)
2. Fine-tune permissions, backends, configuration
3. Grow network and test stability
4. When ready: Activate zero-trust mode
5. Network now fully decentralized forever
```

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
    CXHELLO,      // LAN peer discovery handshake
    CXHELLO_RESPONSE,  // LAN discovery response
    BLOCK_REQUEST,     // Request blockchain blocks
    BLOCK_RESPONSE,    // Respond with blockchain blocks
    CHAIN_STATUS_REQUEST,   // Request blockchain status
    CHAIN_STATUS_RESPONSE,  // Respond with blockchain status
    PEER_LIST_RESPONSE,     // Respond with peer list
    SEED_REQUEST,      // Bootstrap seed request
    SEED_RESPONSE,     // Bootstrap seed response
    ZERO_TRUST_ACTIVATION,  // Zero trust mode activation
    // Extensible via plugins
}
```

### Event Creation & EventBuilder Pattern

**CRITICAL:** All events MUST be created using the EventBuilder pattern with `.signData()` for cryptographic verification.

#### Security Requirement

Since implementing real security checks, **all production events require cryptographic signatures** to pass verification. Manual NetworkEvent creation is deprecated and will fail security validation.

**Why .signData() is Required:**
- Cryptographically signs the event payload (NetworkEvent.d) with sender's private key
- Sets `oCXID` (origin sender ID) for verification
- Creates signed blob that preserves sender authenticity during relay
- Prevents event forgery and tampering
- Required for multi-layer signature verification (NetworkContainer + NetworkEvent + Payload)

#### Three-Layer Signature Architecture

ConnectX uses a **three-layer signature system** for maximum security:

**Layer 1: NetworkContainer Signature**
- Signs the entire NetworkContainer (transport wrapper)
- Verifies transmitter identity (nc.iD)
- Verified in `NodeMesh.processNetworkInput()` before deserialization
- Prevents spoofing of transmitter ID

**Layer 2: NetworkEvent Signature**
- Signs the NetworkEvent object itself
- Verifies event structure integrity
- Ensures event fields haven't been tampered with

**Layer 3: NetworkEvent.d Payload Signature**
- Signs the event payload data (the actual message/data)
- Created by `.signData()` method
- Verifies original sender (oCXID) - preserved across relay
- Most critical for relay scenarios (proves original authorship)

#### EventBuilder API

**Location:** `ConnectX.EventBuilder` (ConnectX.java:633-725)

**Standard Event Creation Pattern:**
```java
// Standard event with signature
connectX.buildEvent(EventType.MESSAGE, payloadData)
    .toPeer(destinationID)
    .signData()  // REQUIRED for Layer 3 payload signature
    .queue();

// Event with network routing
connectX.buildEvent(EventType.BLOCK_REQUEST, requestData)
    .toPeer(peerID)
    .signData()
    .getPath().network = networkID;  // Set network routing
    .queue();

// Event with bridge routing (bootstrap)
connectX.buildEvent(EventType.NewNode, nodeData)
    .toPeer(EPOCH_UUID)
    .viaBridge(bridgeHost, bridgePort)
    .signData()
    .queue();

// E2E encrypted event
connectX.buildEvent(EventType.MESSAGE, secretData)
    .addRecipient(peer1ID)
    .addRecipient(peer2ID)
    .encrypt()  // E2E encryption
    .toNetwork("NETWORKID")
    .queue();
```

#### EventBuilder Methods

**Routing Methods:**
- `.toPeer(String peerID)` - Route to specific peer (CXS scope)
- `.toNetwork(String networkID)` - Broadcast to network (CXN scope)
- `.viaBridge(String host, String port)` - Route via HTTP bridge

**Security Methods:**
- `.signData()` - **REQUIRED** - Sign event payload (Layer 3) for verification
- `.encrypt()` - Enable E2E encryption (auto-includes sender as recipient)
- `.addRecipient(String peerID)` - Add E2E encryption recipient

**Path Modification:**
- `.getPath()` - Access CXPath for advanced routing (network, scope, bridge)

**Execution:**
- `.queue()` - Queue event for transmission

#### Signature Verification Flow

**Outbound Events:**
1. EventBuilder creates NetworkEvent with payload data
2. `.signData()` signs payload (Layer 3) with sender's private key → creates signed blob
3. Sets `event.p.oCXID = sender's cxID`
4. EventBuilder signs NetworkEvent object (Layer 2)
5. OutputBundle queued for transmission
6. OutConnectionController signs NetworkContainer (Layer 1 - transport)
7. All three signatures transmitted

**Inbound Events:**
1. **Layer 1:** NetworkContainer signature verified first (transport - verifies transmitter nc.iD)
2. **Layer 2:** NetworkEvent signature verified (event structure integrity)
3. **Layer 3:** NetworkEvent.d payload signature verified (original sender oCXID)
4. Events without valid signatures at ANY layer → `DecryptionFailureException`
5. Verification failures logged to Analytics

**Relay Integrity:**
- **Layer 1:** Re-signed by each relay hop (proves transmission chain)
- **Layer 2:** Preserved across relay (proves event structure unchanged)
- **Layer 3:** Preserved across relay (proves original sender identity)

#### Migration from Manual Creation

**DEPRECATED PATTERN (DO NOT USE):**
```java
// ❌ Manual NetworkEvent creation - FAILS security verification
NetworkEvent event = new NetworkEvent();
event.eT = EventType.MESSAGE.name();
event.iD = UUID.randomUUID().toString();
event.d = data;  // NO Layer 3 payload signature!

event.p = new CXPath();
event.p.cxID = destinationID;
event.p.oCXID = connectX.getOwnID();  // oCXID set but NO cryptographic binding!
event.p.scope = "CXS";

NetworkContainer nc = new NetworkContainer();
nc.se = "cxJSON1";
nc.s = false;

OutputBundle bundle = new OutputBundle(event, targetNode, null, null, nc);
connectX.queueEvent(bundle);
```

**Problems with Manual Creation:**
- Missing `.signData()` call → no Layer 3 payload signature
- Missing oCXID cryptographic binding (Layer 3)
- Only Layer 1 (container) and Layer 2 (event) signatures present
- Fails Layer 3 verification on recipient side
- Cannot prove original sender in some scenarios

**CORRECT PATTERN (REQUIRED):**
```java
// ✅ EventBuilder pattern - passes ALL three-layer security verification
String payloadJson = ConnectX.serialize("cxJSON1", responseData);
connectX.buildEvent(EventType.MESSAGE, payloadJson.getBytes("UTF-8"))
    .toPeer(destinationID)
    .signData()  // Signs payload (Layer 3), sets oCXID, creates verification-ready event
    .queue();     // Automatically adds Layer 1 & 2 signatures during transmission
```

**Benefits:**
- Automatic Layer 3 payload signing
- oCXID cryptographically bound to payload signature
- All three signature layers present
- Passes all security checks
- Compatible with relay
- Proves original sender identity
- Significantly less code (~75% reduction)

#### Conversion Examples

**Example 1: CXHELLO_RESPONSE**
```java
// BEFORE (20+ lines, manual creation, NO Layer 3 signature)
String responsePayloadJson = ConnectX.serialize("cxJSON1", responsePayload);

NetworkEvent responseEvent = new NetworkEvent();
responseEvent.eT = EventType.CXHELLO_RESPONSE.name();
responseEvent.iD = java.util.UUID.randomUUID().toString();
responseEvent.d = responsePayloadJson.getBytes("UTF-8");  // Unsigned payload!

responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
responseEvent.p.cxID = requesterID;
responseEvent.p.oCXID = connectX.getOwnID();  // Set but not cryptographically bound
responseEvent.p.scope = "CXS";

NetworkContainer responseContainer = new NetworkContainer();
responseContainer.se = "cxJSON1";
responseContainer.s = false;

OutputBundle responseBundle = new OutputBundle(
    responseEvent, requesterNode, null, null, responseContainer);
connectX.queueEvent(responseBundle);

// AFTER (4 lines, EventBuilder, WITH all three signature layers)
String responsePayloadJson = ConnectX.serialize("cxJSON1", responsePayload);
connectX.buildEvent(EventType.CXHELLO_RESPONSE, responsePayloadJson.getBytes("UTF-8"))
    .toPeer(requesterID)
    .signData()  // Layer 3 payload signature + oCXID binding
    .queue();     // Adds Layer 1 & 2 during transmission
```

**Example 2: BLOCK_RESPONSE with Path Preservation**
```java
// BEFORE (25+ lines, manual creation)
String blockJson = ConnectX.serialize("cxJSON1", block);

NetworkEvent blockEvent = new NetworkEvent();
blockEvent.eT = EventType.BLOCK_RESPONSE.name();
blockEvent.iD = java.util.UUID.randomUUID().toString();
blockEvent.d = blockJson.getBytes("UTF-8");

blockEvent.p = new dev.droppinganvil.v3.network.CXPath();
blockEvent.p.cxID = requesterID;
blockEvent.p.oCXID = connectX.getOwnID();
blockEvent.p.scope = originalEvent.p.scope;
blockEvent.p.network = originalEvent.p.network;
blockEvent.p.bridge = originalEvent.p.bridge;
blockEvent.p.bridgeArg = originalEvent.p.bridgeArg;

NetworkContainer nc = new NetworkContainer();
nc.se = "cxJSON1";
nc.s = false;

OutputBundle bundle = new OutputBundle(blockEvent, requesterNode, null, null, nc);
connectX.queueEvent(bundle);

// AFTER (8 lines, EventBuilder, WITH all signature layers)
String blockJson = ConnectX.serialize("cxJSON1", block);

ConnectX.EventBuilder eb = connectX.buildEvent(
    EventType.BLOCK_RESPONSE, blockJson.getBytes("UTF-8"))
    .toPeer(requesterID)
    .signData();  // Layer 3 payload signature

// Preserve original path routing
if (originalEvent.p != null) {
    eb.getPath().network = originalEvent.p.network;
    eb.getPath().scope = originalEvent.p.scope;
    eb.getPath().bridge = originalEvent.p.bridge;
    eb.getPath().bridgeArg = originalEvent.p.bridgeArg;
}
eb.queue();
```

**Example 3: Bootstrap Event (NEWNODE)**
```java
// BEFORE (35+ lines, manual creation)
String selfJson = serialize("cxJSON1", self);

NetworkEvent newNodeEvent = new NetworkEvent();
newNodeEvent.eT = EventType.NewNode.name();
newNodeEvent.iD = java.util.UUID.randomUUID().toString();
newNodeEvent.d = selfJson.getBytes("UTF-8");

newNodeEvent.p = new CXPath();
newNodeEvent.p.cxID = EPOCH_UUID;
newNodeEvent.p.oCXID = getOwnID();
newNodeEvent.p.scope = "CXS";
newNodeEvent.p.bridge = bridgeHost;
newNodeEvent.p.bridgeArg = bridgePort;

NetworkContainer nc = new NetworkContainer();
nc.se = "cxJSON1";
nc.s = false;

Node bridgeNode = new Node();
bridgeNode.cxID = EPOCH_UUID;

OutputBundle bundle = new OutputBundle(newNodeEvent, bridgeNode, null, null, nc);
queueEvent(bundle);

// AFTER (9 lines, EventBuilder, WITH all signature layers)
String selfJson = serialize("cxJSON1", self);

String[] bridgeParts = EPOCH_BRIDGE_ADDRESS.split(":", 2);
buildEvent(EventType.NewNode, selfJson.getBytes("UTF-8"))
    .toPeer(EPOCH_UUID)
    .viaBridge(bridgeParts[0], bridgeParts[1])
    .signData()  // Layer 3 payload signature (critical for relay)
    .queue();
```

#### Special Case: LANScanner CXHELLO

**Exception:** `LANScanner.sendCXHELLO()` (LANScanner.java:204-240) intentionally uses manual NetworkEvent creation.

**Why:** CXHELLO is a bootstrap discovery message sent to unknown peers before peer ID is known. It uses the NEWNODE manual pattern for direct transmission fallback in OutConnectionController.

**Pattern:**
```java
// Manual creation for bootstrap to unknown peer (NO peer ID available)
NetworkEvent helloEvent = new NetworkEvent(EventType.CXHELLO, payloadBytes);
helloEvent.eT = EventType.CXHELLO.name();
helloEvent.iD = UUID.randomUUID().toString();
// DON'T set event.p (Permission/CXPath) - triggers direct transmission fallback

Node targetNode = new Node();
targetNode.addr = targetIP + ":" + targetPort;  // Only address, no peer ID

OutputBundle bundle = new OutputBundle(helloEvent, targetNode, null, null, nc);
connectX.queueEvent(bundle);
```

**Important:** This is the ONLY acceptable use case for manual creation - when peer ID is unknown and direct socket transmission to address is required.

#### Verification Failure Handling

**Common Failure Scenarios:**

1. **Event missing Layer 3 payload signature** → `DecryptionFailureException`
   - Cause: Manual NetworkEvent creation without `.signData()`
   - Fix: Use EventBuilder pattern with `.signData()`

2. **Invalid signature (any layer)** → `DecryptionFailureException`
   - Cause: Data modified after signing, or wrong signing key
   - Fix: Ensure data is not modified after `.signData()` call

3. **oCXID mismatch** → Security warning
   - Cause: oCXID field doesn't match Layer 3 payload signature
   - Fix: Use EventBuilder (automatically sets correct oCXID)

4. **Unknown sender on non-bootstrap event** → `DecryptionFailureException`
   - Cause: Receiving event from peer whose public key is not imported
   - Fix: Only CXHELLO and NewNode allowed from unknown senders

**Analytics Logging:**
All verification failures are logged:
```java
Analytics.addData(AnalyticData.Tear,
    "NetworkContainer signature verification FAILED for " + containerID);
Analytics.addData(AnalyticData.Tear,
    "NetworkEvent signature verification FAILED for " + eventID);
Analytics.addData(AnalyticData.Tear,
    "Payload signature verification FAILED for " + oCXID);
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

### Zero Trust Mode

**Zero Trust Mode** is an **irreversible** network transition that converts a centrally-controlled network into a fully decentralized P2P mesh.

#### Purpose & Use Case

**Intended workflow:**
1. **Network Creation:** NMI creates network with centralized control for initial configuration
2. **Fine-Tuning:** NMI configures permissions, blockchain settings, backend infrastructure
3. **Stabilization:** Network operates in centralized mode until ready for production
4. **Zero Trust Activation:** NMI activates zero trust mode, permanently decentralizing the network

#### Activation Process

**Method:** `NMI.startZeroTrust(networkID)`

**Steps:**
1. NMI creates `ZERO_TRUST_ACTIVATION` event with updated seed (zT=true)
2. Event is signed by NMI and recorded to c1 (Admin chain)
3. Event is distributed CXN-scope to all network participants
4. Each node receives event and applies zero trust mode:
   - Sets `network.zT = true`
   - Applies new seed (without blockchain data)
   - Triggers blockchain re-sync using zero trust consensus protocols

**Event Payload:**
```json
{
  "network": "NETWORKID",
  "seed": {
    "...": "updated seed data with zT=true"
  }
}
```

**Requirements:**
- Requires NMI signature
- Recorded to c1 (Admin chain)
- Sets `executeOnSync = true` (state-modifying event)

#### Changes After Activation

**CRITICAL: This operation is IRREVERSIBLE**

Once activated:

**1. NMI Permission Blocking (CXNetwork.java:66-110)**
- NMI loses ALL special permissions
- `checkChainPermission()` blocks NMI if `zT == true`
- `checkNetworkPermission()` blocks NMI if `zT == true`
- `getVariableNetworkPermission()` blocks NMI if `zT == true`
- NMI is treated as a regular node for all permission checks

**Implementation:**
```java
// Zero Trust Mode: Block NMI from using permissions
if (zT && isCurrentNMI(deviceID)) {
    System.out.println("[ZT-Blocked] NMI blocked from permission: " + permission);
    return false;
}
```

**2. Blockchain Consensus Switch**
- **Before zT:** NMI is trusted authority for blockchain (single source of truth)
- **After zT:** Multi-peer voting consensus (requires block reconciliation algorithm)
- **Implication:** Blockchain conflicts resolved by peer majority, not NMI decree

**3. Network Configuration Lock**
- NMI can no longer:
  - Modify network configuration
  - Grant/revoke permissions
  - Add/remove backend nodes
  - Perform administrative actions
- Network becomes fully peer-governed

**4. Permission Map Retention**
- NMI permissions remain in `networkPermissions.permissionSet`
- **Rationale:** Required to securely process the ZERO_TRUST_ACTIVATION event
- **Enforcement:** Permission checks actively block NMI despite map entries

#### Security Considerations

**Why Irreversible?**
- Prevents NMI from reclaiming centralized control after decentralization
- Ensures trust transition is permanent and verifiable
- Protects network participants from governance reversal

**Why Keep NMI in Permission Map?**
- ZERO_TRUST_ACTIVATION event requires NMI signature verification
- Permission system needs NMI entry to validate the activation event
- Blockchain replay needs to process historical NMI actions
- Active blocking (`if zT && isNMI`) prevents actual permission use

**NMI Identification:**
```java
private boolean isCurrentNMI(String deviceID) {
    return configuration.backendSet.get(0).equals(deviceID);
}
```
- NMI is always the **first backend** in `backendSet`
- Set during network creation (ConnectX.createNetwork():1290)
- Immutable after zero trust activation

#### Event Type Reference

**EventType.ZERO_TRUST_ACTIVATION** (EventType.java:165-188)

```java
/**
 * Activate Zero Trust mode for a network (NMI-only, irreversible)
 * Payload format: JSON {"network": "NETWORKID", "seed": {...}}
 * Recorded to c1 (Admin) chain as the source of truth
 * Requires NMI signature and ZeroTrustActivation permission (NMI-only)
 * Sets executeOnSync = true (state-modifying event)
 */
ZERO_TRUST_ACTIVATION
```

**Documentation:** See EventType.java lines 165-187 for full event documentation

#### Implementation Status

**Completed:**
- [DONE] Zero trust flag (`CXNetwork.zT`)
- [DONE] NMI permission blocking in all permission check methods (CXNetwork.java:66-110)
- [DONE] `ZERO_TRUST_ACTIVATION` event type (EventType.java:165-188)
- [DONE] `startZeroTrust()` method implementation (ConnectX.java:1399-1479)
- [DONE] ZERO_TRUST_ACTIVATION event handler (NodeMesh.java:1587-1665)
- [DONE] Multi-peer block consensus algorithm (BlockConsensusTracker.java)
- [DONE] Hybrid consensus protocol (EPOCH/NMI + multi-peer voting)
- [DONE] Per-network consensus configuration (CXNetwork.java:50-78)

**Pending:**
- [TODO] Integration tests for zero trust activation
- [TODO] Multi-peer block request broadcasting

---

## Block Consensus Mechanism

ConnectX implements a hybrid consensus system that supports both centralized (EPOCH/NMI-trust) and decentralized (multi-peer voting) block verification.

### Consensus Architecture

**Two Modes:**

1. **EPOCH/NMI Trust Mode** (Default, non-zT networks)
   - EPOCH/NMI responses are authoritative
   - Blocks from EPOCH/NMI accepted immediately
   - Peer responses use multi-peer consensus

2. **Zero Trust Mode** (After zT activation)
   - All responses (including EPOCH/NMI) use multi-peer consensus
   - Majority voting determines block validity
   - No single source of truth

### BlockConsensusTracker

**Location:** `BlockConsensusTracker.java`

**Purpose:** Manages multi-peer block requests and responses to reach consensus through majority voting.

**Components:**

1. **BlockRequest Class**
   - Tracks requests across multiple peers
   - Stores responses with block hash comparison
   - Network-specific consensus configuration
   - Timeout handling

2. **Consensus Algorithm**
   ```
   For each block request:
   1. Query multiple peers for same block
   2. Calculate hash for each response
   3. Count hash occurrences
   4. Check if majority reaches threshold
   5. Return consensus block if threshold met
   ```

### Per-Network Consensus Configuration

**Location:** `CXNetwork.java` lines 50-78

Each network can configure its own consensus parameters:

```java
public Integer consensusMinPeers = 3;           // Minimum peers required
public Double consensusMinResponseRate = 0.6;   // 60% must respond
public Double consensusThreshold = 0.67;        // 67% must agree
public Long consensusTimeoutMs = 30000L;        // 30 second timeout
```

**Considerations:**

| Network Type | Min Peers | Response Rate | Threshold | Rationale |
|-------------|-----------|---------------|-----------|-----------|
| Test Network | 2 | 0.5 (50%) | 0.5 (51%) | Fast consensus for testing |
| Production | 3 | 0.6 (60%) | 0.67 (67%) | Balanced security/performance |
| High Security | 5 | 0.75 (75%) | 0.75 (75%) | Strong consensus guarantees |
| Zero Trust | 7 | 0.8 (80%) | 0.8 (80%) | Maximum security |

### Consensus Flow

**BLOCK_RESPONSE Handler** (NodeMesh.java:1294-1393)

```
Block response received
    ↓
Is sender EPOCH/NMI?
    ↓ YES → Is network in zT mode?
        ↓ NO  → Accept immediately (authoritative)
        ↓ YES → Use consensus ↓
    ↓ NO  → Use consensus
        ↓
Record response in BlockConsensusTracker
    ↓
Check consensus:
  - Enough peers responded? (≥ minPeers)
  - Response rate sufficient? (≥ minResponseRate)
  - Majority agreement? (≥ consensusThreshold)
    ↓ YES → Apply consensus block
    ↓ NO  → Wait for more responses
```

### Block Hash Calculation

**Method:** `BlockConsensusTracker.calculateBlockHash()`

Hash includes:
- Block number
- Chain ID
- Previous block hash
- Event count

**Purpose:** Quickly compare blocks from different peers without full content comparison.

### Consensus Safety

**Protection Against:**

1. **Byzantine Peers:** Majority voting prevents single bad actor
2. **Network Partitions:** Timeout prevents indefinite waiting
3. **Slow Peers:** Minimum response rate ensures timely consensus
4. **Conflicting Blocks:** Threshold requires supermajority agreement

**Failure Modes:**

- **Timeout:** Request cleaned up after `consensusTimeoutMs`
- **Insufficient Peers:** Consensus fails if `< minPeers` respond
- **Low Response Rate:** Consensus fails if `< minResponseRate`
- **No Agreement:** Consensus fails if no hash reaches `consensusThreshold`

### Integration with Blockchain

**Block Application** (NodeMesh.java:1974-2037)

After consensus reached:
1. Add block to chain in memory
2. Update current block pointer
3. Persist block to disk
4. Prepare block (verify signatures)
5. Queue state-modifying events

**State Replay:**

Events marked with `executeOnSync = true` are queued during blockchain sync to rebuild network state:
- REGISTER_NODE
- GRANT_PERMISSION
- REVOKE_PERMISSION
- ZERO_TRUST_ACTIVATION
- BLOCK_NODE
- UNBLOCK_NODE

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

### Node Addressing

**Hybrid Address Model:**

Nodes in ConnectX can operate with multiple address types:

1. **Direct P2P Address** (IP:Port)
   - Discovered via LAN scanner (CXHELLO protocol)
   - Not required during initialization
   - Automatically populated for nodes with direct connectivity

2. **HTTP Bridge Address**
   - Format: `cxHTTP1:https://bridge.example.com/cx`
   - Required for nodes behind firewalls/NAT
   - Enables connectivity without direct P2P access

3. **No Address** (Bridge-only nodes)
   - Nodes can launch without any P2P address
   - Rely solely on HTTP bridge for communication
   - Suitable for restricted network environments

**Initialization:** (ConnectX.java:154-157)
```java
// Address is optional - nodes can rely solely on HTTP bridge addresses
// For nodes with direct P2P connectivity, address will be discovered via CXHELLO
String address = null;  // Will be populated by LAN discovery or bridge
```

**Fair Testing:**

- Removed hardcoded `127.0.0.1` localhost address
- Enables distributed testing across multiple machines
- Supports consensus testing with real network topology
- Allows hybrid deployments (P2P + bridge nodes)

### Network Isolation

Each `ConnectX` instance represents one peer:
- Own working directory (`cxRoot`)
- Own keys and configuration
- Own event/output queues
- Shared global `PeerDirectory`

### Debug Logging Configuration

**Version:** 3.0.2
**Updated:** 2025-12-30

ConnectX provides fine-grained control over debug logging to balance troubleshooting needs with log cleanliness.

#### OUT-LOOP Logging Control

**Location:** `NodeConfig.enableOutLoopLogging`

```java
// NodeConfig.java
public static boolean enableOutLoopLogging = false;  // Default: disabled
```

**Purpose:** Controls OutputProcessor iteration logging

**When Enabled:**
```
[OUT-LOOP] Peer e646eb13 iteration 100, queue: 0, polled: false
[OUT-LOOP] Peer e646eb13 iteration 200, queue: 0, polled: false
[OUT-LOOP] Peer e646eb13 iteration 300, queue: 5, polled: true
```

**Logging Behavior:**
- Logs every 100 iterations of the output processor loop
- Logs when queue size > 5 (regardless of iteration count)
- Shows: peer ID, iteration count, queue size, poll result

**Use Cases:**
- **Enabled:** Diagnosing output queue processing issues, stuck queues, or throughput problems
- **Disabled:** Normal operation - reduces log noise significantly

**Configuration:**
```java
// Enable for debugging
NodeConfig.enableOutLoopLogging = true;

// Disable for production (default)
NodeConfig.enableOutLoopLogging = false;
```

**Impact:**
- Active networks can generate hundreds of OUT-LOOP messages per minute
- Recommended to keep disabled unless specifically debugging output queue issues

#### Other Debug Logging

**Existing Debug Flags:**
```java
// NodeConfig.java
public static boolean devMode = true;               // Development mode logging
public static boolean autoUpdate = true;            // Auto-update features
public static boolean revealVersion = true;         // Version info in comms
public static boolean supportUnavailableServices = false;  // Legacy service support
```

**Future Enhancements:**
Additional debug flags may be added to Configuration.java for per-network logging control:
```java
// Future: Per-network debug configuration
Configuration {
    Boolean debugMode = false;           // Network-level verbose logging
    Boolean logIncomingEvents = false;   // Log all incoming events
    Boolean logOutgoingEvents = false;   // Log all outgoing events
    Boolean logBlockchainOps = false;    // Log blockchain operations
}
```

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