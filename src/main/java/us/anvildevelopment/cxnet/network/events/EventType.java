package us.anvildevelopment.cxnet.network.events;

/**
 * Each event type declares output pipeline enforcement properties:
 *   ttlMs           - How long (ms) the event may sit in the output/retry queue before being dropped (0 = no expiry)
 *   maxPayloadBytes - Hard cap on payload size in bytes
 *   maxRetries      - Maximum delivery attempts before the event is discarded
 */
public enum EventType {
    DICTIONARYEDIT(0, 65_536, 4),
    /**
     * Resource common to all nodes has been updated
     */
    GLOBALRESOURCEUPDATE(0, 1_048_576, 4),
    /**
     * device joined cx network
     */
    NewNode(0, 8_192, 10),
    ResourceModification(0, 1_048_576, 4),
    /**
     * Schedules a network restart
     */
    RESTART(0, 1_024, 4),
    /**
     * Used when transmitting initial data
     */
    HELLOWORLD(0, 65_536, 4),
    /**
     * LAN peer discovery request
     * Sent to local IP range to discover peers on same network
     * Response includes peer ID and listening port for direct local communication
     * Enables firewall traversal and performance optimization
     */
    CXHELLO(30_000, 8_192, 3),
    /**
     * Response to CXHELLO request
     * Payload format: JSON {"peerID": "UUID", "port": 49152, "networks": ["CXNET"]}
     * Allows peers to map peer IDs to local IP addresses for direct communication
     */
    CXHELLO_RESPONSE(30_000, 8_192, 3),
    /**
     * Internal Messaging
     */
    MESSAGE(300_000, 1_048_576, 50),
    PeerFinding(60_000, 4_096, 5),
    /**
     * Request official network seed from NMI
     * Used for bootstrapping new nodes into CXNET
     */
    SEED_REQUEST(60_000, 2_048, 10),
    /**
     * Response to SEED_REQUEST containing seed data
     * Payload contains serialized Seed object
     */
    SEED_RESPONSE(120_000, 10_485_760, 5),
    /**
     * Update existing Network Master Identity (NMI)
     * Allows networks to update their NMI credentials
     * Requires existing NMI signature and appropriate permissions
     * Note: CXNET NMI is immutable (exception conditions to be added later)
     */
    UPDATE_NMI(0, 4_096, 4),
    /**
     * Add new Network Master Identity (NMI)
     * Allows networks to add additional NMIs for redundancy
     * Requires existing NMI signature and appropriate permissions
     */
    ADD_NMI(0, 4_096, 4),
    /**
     * Delete Network Master Identity (NMI)
     * Allows networks to remove NMIs
     * Requires existing NMI signature and appropriate permissions
     */
    DELETE_NMI(0, 4_096, 4),
    /**
     * Request a specific block from a peer
     * Payload format: JSON {"network": "NETWORKID", "chain": 3, "block": 5}
     * Used by new nodes to sync blockchain one block at a time
     */
    BLOCK_REQUEST(60_000, 1_024, 5),
    /**
     * Response to BLOCK_REQUEST containing block data
     * Payload contains serialized NetworkBlock object
     * Allows new nodes to sync and validate blocks chronologically
     */
    BLOCK_RESPONSE(120_000, 10_485_760, 5),
    /**
     * Request blockchain metadata (current block heights)
     * Payload format: JSON {"network": "NETWORKID"}
     * Returns current block height for each chain (c1, c2, c3)
     */
    CHAIN_STATUS_REQUEST(30_000, 512, 3),
    /**
     * Response to CHAIN_STATUS_REQUEST
     * Payload format: JSON {"c1": 10, "c2": 25, "c3": 150}
     * Tells requesting node how many blocks exist in each chain
     */
    CHAIN_STATUS_RESPONSE(30_000, 1_024, 3),
    /**
     * Block a node from the network (CXNET-level or network-specific)
     * Payload format: JSON {"network": "NETWORKID", "nodeID": "UUID", "reason": "spam"}
     * If network is "CXNET", blocks at CXNET level (all transmissions rejected)
     * If network is specific network ID, blocks only from that network
     * Requires BlockNode permission (NMI-only or designated moderators)
     * Sets executeOnSync = true (state-modifying event)
     */
    BLOCK_NODE(0, 1_024, 4),
    /**
     * Unblock a previously blocked node
     * Payload format: JSON {"network": "NETWORKID", "nodeID": "UUID"}
     * Reverses BLOCK_NODE action
     * Requires UnblockNode permission
     * Sets executeOnSync = true (state-modifying event)
     */
    UNBLOCK_NODE(0, 1_024, 4),
    /**
     * Request random peer IPs for discovery
     * Payload format: JSON {"count": 10} (optional, defaults to 30% of known peers or max 10)
     * Response contains only IP addresses (not full Node objects)
     * Rate limited: 3 requests per IP per hour per node
     * Requesting node must then contact each IP for Node info/seed
     */
    PEER_LIST_REQUEST(30_000, 512, 3),
    /**
     * Response to PEER_LIST_REQUEST
     * Payload format: JSON {"ips": ["192.168.1.100:49152", "10.0.0.5:49153", ...]}
     * Contains only IP:port combinations
     * Receiving node must manually request NewNode/SEED from each IP
     */
    PEER_LIST_RESPONSE(30_000, 8_192, 3),
    /**
     * Register a node to a network (for whitelist/private networks)
     * Payload format: JSON {"network": "NETWORKID", "nodeID": "UUID", "approver": "APPROVER_UUID"}
     * Recorded to c1 (Admin) chain as the source of truth
     * System reads c1 to populate approved nodes list for whitelist mode
     * Requires RegisterNode permission (NMI-only or designated approvers)
     * Sets executeOnSync = true (state-modifying event)
     *
     * Whitelist Mode Behavior:
     * - Nodes cannot join network unless REGISTER_NODE event exists in c1
     * - During bootstrap, system checks c1 for registration entry
     * - If not found and whitelist mode enabled: reject connection
     */
    REGISTER_NODE(0, 2_048, 4),
    /**
     * Grant permission to a node for a specific chain
     * Payload format: JSON {"network": "NETWORKID", "nodeID": "UUID", "permission": "Record", "chain": 3, "priority": 10}
     * Recorded to c1 (Admin) chain as the source of truth
     * System reads c1 during blockchain replay to rebuild permission state
     * Requires GrantPermission permission (NMI-only or designated admins)
     * Sets executeOnSync = true (state-modifying event)
     *
     * When received (live or during sync):
     * - Adds permission entry to network.networkPermissions.permissionSet
     * - Permission key format: "PermissionName-ChainID"
     * - Persists across restarts via blockchain replay
     */
    GRANT_PERMISSION(0, 2_048, 4),
    /**
     * Revoke permission from a node for a specific chain
     * Payload format: JSON {"network": "NETWORKID", "nodeID": "UUID", "permission": "Record", "chain": 3}
     * Recorded to c1 (Admin) chain as the source of truth
     * Requires RevokePermission permission (NMI-only or designated admins)
     * Sets executeOnSync = true (state-modifying event)
     *
     * When received (live or during sync):
     * - Removes permission entry from network.networkPermissions.permissionSet
     * - Persists across restarts via blockchain replay
     */
    REVOKE_PERMISSION(0, 2_048, 4),
    /**
     * Activate Zero Trust mode for a network (NMI-only, irreversible)
     * Payload format: JSON {"network": "NETWORKID", "seed": {...}} (includes updated seed with zT=true)
     * Recorded to c1 (Admin) chain as the source of truth
     * Requires NMI signature and ZeroTrustActivation permission (NMI-only)
     * Sets executeOnSync = true (state-modifying event)
     *
     * CRITICAL: This operation is IRREVERSIBLE
     * After activation:
     * - NMI loses all special permissions (treated as regular node)
     * - Network becomes fully decentralized P2P mesh
     * - Blockchain consensus switches from NMI-trust to multi-peer voting
     * - NMI can no longer make edits to network configuration
     * - Network operates in zero trust mode permanently
     *
     * When received (live or during sync):
     * - Sets network.zT = true
     * - Applies new seed (without blockchain data) to all peers
     * - Triggers blockchain re-sync using zero trust consensus protocols
     * - NMI permissions remain in permission map but are no longer honored
     *
     * Use case: Networks created centrally for fine-tuning, then switched to zero trust once stabilized
     */
    ZERO_TRUST_ACTIVATION(0, 10_485_760, 4),
    /**
     * Direct stream session orchestration (OPEN, ACCEPT, CLOSE).
     * Payload: {@code CXStream} serialized as cxJSON1, sent E2E encrypted via
     * {@code buildEvent(...).toPeer(id).encrypt(id).queue()}.
     * The data channel itself is a separate TCP socket or WebSocket connection
     * using AES-GCM-256 (or another pluggable cipher negotiated in the OPEN payload).
     */
    STREAM(0, 65_536, 5),
    /**
     * Network epoch registration request.
     * Sent by a network creator to the CXNET backend set to register a new network.
     * Payload: {@code NetworkEpoch} containing the CXIK and the signed seed blob for the new network.
     * CXNET NMI validates the CXIK (one-time use), imports the network, re-signs the seed,
     * and responds with NETEPOCH_RESPONSE.
     */
    NETEPOCH(300_000, 10_485_760, 10),
    /**
     * Response to NETEPOCH from the CXNET NMI.
     * Payload: {@code NetworkEpoch} with success flag and (on success) an NMI-signed seed blob
     * for the newly imported network.
     */
    NETEPOCH_RESPONSE(120_000, 10_485_760, 5),
    /**
     * Check whether a network name is already registered with the CXNET NMI.
     * Sent via toBackends("CXNET") so all backends receive it; qd deduplication ensures
     * only one backend processes and responds.
     * Payload: {@code NetworkNameCheck} with networkName and requestId set.
     */
    CHECK_NETWORK_NAME(30_000, 512, 3),
    /**
     * Response to CHECK_NETWORK_NAME from a CXNET backend.
     * Payload: {@code NetworkNameCheck} with requestId echoed, taken flag, and message.
     */
    CHECK_NETWORK_NAME_RESPONSE(30_000, 1_024, 3),
    ;

    /** How long (ms) this event may sit in the output/retry queue before being dropped. 0 means no expiry. */
    public final long ttlMs;

    /** Hard cap on payload size in bytes. */
    public final int maxPayloadBytes;

    /** Maximum delivery attempts before the event is discarded. */
    public final int maxRetries;

    EventType(long ttlMs, int maxPayloadBytes, int maxRetries) {
        this.ttlMs = ttlMs;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxRetries = maxRetries;
    }
}
