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
    ;
}
