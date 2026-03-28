/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

/**
 * Payload for BLOCK_NODE and UNBLOCK_NODE events.
 * One class is used for both event types. Direction is determined solely by the
 * EventType constant on the NetworkEvent, not by the presence or absence of any field.
 * Fields not relevant to a given event type are left null.
 */
public class NodeModeration {
    /** Network ID the action applies to, or "CXNET" for a global block */
    public String network;
    /** ID of the node being blocked or unblocked */
    public String nodeID;
    /** Optional reason. Not applicable to UNBLOCK_NODE but may be included for audit trail. */
    public String reason;

    public NodeModeration() {}

    public NodeModeration(String network, String nodeID, String reason) {
        this.network = network;
        this.nodeID = nodeID;
        this.reason = reason;
    }
}
