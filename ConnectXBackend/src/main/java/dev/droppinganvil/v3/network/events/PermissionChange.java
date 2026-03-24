/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.events;

/**
 * Payload for GRANT_PERMISSION and REVOKE_PERMISSION events.
 * One class serves both event types. Direction is determined solely by the
 * EventType constant on the NetworkEvent, not by the presence or absence of any field.
 * Fields not relevant to a given event type are left null.
 */
public class PermissionChange {
    /** Network ID the permission applies to */
    public String network;
    /** ID of the node receiving or losing the permission */
    public String nodeID;
    /** Permission name (e.g. "Record") */
    public String permission;
    /** Chain the permission applies to (1, 2, or 3) */
    public Long chain;
    /** Priority weight. Not applicable to REVOKE_PERMISSION. */
    public Integer priority;

    public PermissionChange() {}

    public PermissionChange(String network, String nodeID, String permission, Long chain, Integer priority) {
        this.network = network;
        this.nodeID = nodeID;
        this.permission = permission;
        this.chain = chain;
        this.priority = priority;
    }
}
