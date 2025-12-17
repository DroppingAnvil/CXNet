/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3;

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

}