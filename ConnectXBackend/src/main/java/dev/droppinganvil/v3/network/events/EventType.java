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
    ;
}
