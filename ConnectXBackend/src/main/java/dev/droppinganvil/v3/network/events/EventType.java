package dev.droppinganvil.v3.network.events;

public enum EventType {
    DICTIONARYEDIT,
    /**
     * Resource common to all nodes has been updated
     */
    GLOBALRESOURCEUPDATE,
    /**
     * device joined cx network
     */
    NewNode,
    ResourceModification,
    /**
     * Schedules a network restart
     */
    RESTART,
    /**
     * Used when transmitting initial data
     */
    HELLOWORLD,
    /**
     * LAN peer discovery request
     * Sent to local IP range to discover peers on same network
     * Response includes peer ID and listening port for direct local communication
     * Enables firewall traversal and performance optimization
     */
    CXHELLO,
    /**
     * Response to CXHELLO request
     * Payload format: JSON {"peerID": "UUID", "port": 49152, "networks": ["CXNET"]}
     * Allows peers to map peer IDs to local IP addresses for direct communication
     */
    CXHELLO_RESPONSE,
    /**
     * Internal Messaging
     */
    MESSAGE,
    PeerFinding,
    /**
     * Request official network seed from NMI
     * Used for bootstrapping new nodes into CXNET
     */
    SEED_REQUEST,
    /**
     * Response to SEED_REQUEST containing seed data
     * Payload contains serialized Seed object
     */
    SEED_RESPONSE,
    /**
     * Update existing Network Master Identity (NMI)
     * Allows networks to update their NMI credentials
     * Requires existing NMI signature and appropriate permissions
     * Note: CXNET NMI is immutable (exception conditions to be added later)
     */
    UPDATE_NMI,
    /**
     * Add new Network Master Identity (NMI)
     * Allows networks to add additional NMIs for redundancy
     * Requires existing NMI signature and appropriate permissions
     */
    ADD_NMI,
    /**
     * Delete Network Master Identity (NMI)
     * Allows networks to remove NMIs
     * Requires existing NMI signature and appropriate permissions
     */
    DELETE_NMI,
    /**
     * Request a specific block from a peer
     * Payload format: JSON {"network": "NETWORKID", "chain": 3, "block": 5}
     * Used by new nodes to sync blockchain one block at a time
     */
    BLOCK_REQUEST,
    /**
     * Response to BLOCK_REQUEST containing block data
     * Payload contains serialized NetworkBlock object
     * Allows new nodes to sync and validate blocks chronologically
     */
    BLOCK_RESPONSE,
    /**
     * Request blockchain metadata (current block heights)
     * Payload format: JSON {"network": "NETWORKID"}
     * Returns current block height for each chain (c1, c2, c3)
     */
    CHAIN_STATUS_REQUEST,
    /**
     * Response to CHAIN_STATUS_REQUEST
     * Payload format: JSON {"c1": 10, "c2": 25, "c3": 150}
     * Tells requesting node how many blocks exist in each chain
     */
    CHAIN_STATUS_RESPONSE,
    /**
     * Block a node from the network (CXNET-level or network-specific)
     * Payload format: JSON {"network": "NETWORKID", "nodeID": "UUID", "reason": "spam"}
     * If network is "CXNET", blocks at CXNET level (all transmissions rejected)
     * If network is specific network ID, blocks only from that network
     * Requires BlockNode permission (NMI-only or designated moderators)
     * Sets executeOnSync = true (state-modifying event)
     */
    BLOCK_NODE,
    /**
     * Unblock a previously blocked node
     * Payload format: JSON {"network": "NETWORKID", "nodeID": "UUID"}
     * Reverses BLOCK_NODE action
     * Requires UnblockNode permission
     * Sets executeOnSync = true (state-modifying event)
     */
    UNBLOCK_NODE,
    /**
     * Request random peer IPs for discovery
     * Payload format: JSON {"count": 10} (optional, defaults to 30% of known peers or max 10)
     * Response contains only IP addresses (not full Node objects)
     * Rate limited: 3 requests per IP per hour per node
     * Requesting node must then contact each IP for Node info/seed
     */
    PEER_LIST_REQUEST,
    /**
     * Response to PEER_LIST_REQUEST
     * Payload format: JSON {"ips": ["192.168.1.100:49152", "10.0.0.5:49153", ...]}
     * Contains only IP:port combinations
     * Receiving node must manually request NewNode/SEED from each IP
     */
    PEER_LIST_RESPONSE,
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
    REGISTER_NODE,
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
    GRANT_PERMISSION,
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
    REVOKE_PERMISSION,
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
    ZERO_TRUST_ACTIVATION,
    ;
}
