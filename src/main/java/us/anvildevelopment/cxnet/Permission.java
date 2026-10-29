/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet;

public enum Permission {
    AddResource,
    NetworkUpload,
    UploadGlobalResource,
    AddAccount,
    Record,
    Transmit,
    /**
     * Permission to block nodes at network level
     * Typically NMI-only or designated moderators
     * Can block at CXNET level (blocks all transmissions) or network-specific level
     */
    BlockNode,
    /**
     * Permission to unblock previously blocked nodes
     * Typically NMI-only or designated moderators
     */
    UnblockNode,
    /**
     * Permission to register nodes to a network (whitelist/private networks)
     * Used in whitelist mode where nodes must be explicitly approved
     * REGISTER_NODE events are recorded to c1 (Admin) chain
     * System reads c1 to populate approved nodes list
     * Typically NMI-only or designated approvers
     */
    RegisterNode,
    /**
     * Permission to grant chain or network permissions to another node
     * Typically NMI-only or designated administrators
     */
    GrantPermission,
    /**
     * Permission to revoke chain or network permissions from another node
     * Typically NMI-only or designated administrators
     */
    RevokePermission,
    /**
     * Permission to originate a broadcast into a network's scope.
     *
     * Resolved hierarchically as {@code <network>.broadcast.<eventType>} rather than by this flat
     * name, so a grant can cover one event type, a plugin namespace, or everything. Checked
     * against an event's verified ORIGIN.
     *
     * CXNET is closed by default: only its backendSet and EPOCH hold it. Every other network
     * grants it to DEFAULT_NODE by default, so member networks keep an open multihop mesh.
     */
    Broadcast,
    /**
     * Permission to forward a broadcast received from another peer.
     *
     * Resolved as {@code <network>.relay.<eventType>} and checked against the authenticated
     * TRANSMITTER. Separate from Broadcast because origination and forwarding cannot be the same
     * check without either dropping legitimate backend relays or dying at the second hop.
     * Granted to DEFAULT_NODE by default, including on CXNET.
     */
    Relay,
    /**
     * Permission to grant or revoke broadcast and relay permissions.
     * Typically NMI-only or designated administrators.
     */
    GrantBroadcast,

}