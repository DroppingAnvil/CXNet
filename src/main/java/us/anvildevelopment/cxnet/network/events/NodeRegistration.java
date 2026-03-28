/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

/**
 * Payload for REGISTER_NODE events.
 * Direction is determined solely by the EventType constant on the NetworkEvent.
 */
public class NodeRegistration {
    /** Network ID to register the node on */
    public String network;
    /** ID of the node being registered */
    public String nodeID;
    /** ID of the approving node (NMI or backend) */
    public String approver;

    public NodeRegistration() {}

    public NodeRegistration(String network, String nodeID, String approver) {
        this.network = network;
        this.nodeID = nodeID;
        this.approver = approver;
    }
}
