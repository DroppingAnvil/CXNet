# ConnectX (CX) Protocol Documentation

**Version:** 3.0
**Last Updated:** 2025-01-23

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