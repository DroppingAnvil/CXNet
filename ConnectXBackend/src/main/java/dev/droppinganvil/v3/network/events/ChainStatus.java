/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.events;

/**
 * Payload for CHAIN_STATUS_REQUEST and CHAIN_STATUS_RESPONSE events.
 * One class serves both event types. Direction is determined solely by the
 * EventType constant on the NetworkEvent, not by the presence or absence of any field.
 * Fields not relevant to a given event type are left null.
 *
 * CHAIN_STATUS_REQUEST: only {@code network} is set.
 * CHAIN_STATUS_RESPONSE: all chain height fields are also set.
 */
public class ChainStatus {
    /** Network ID the request or response applies to */
    public String network;
    /** Chain 1 block height. Response only. */
    public Long c1;
    /** Chain 2 block height. Response only. */
    public Long c2;
    /** Chain 3 block height. Response only. */
    public Long c3;

    public ChainStatus() {}

    /** Convenience constructor for a CHAIN_STATUS_REQUEST payload. */
    public ChainStatus(String network) {
        this.network = network;
    }

    public ChainStatus(String network, Long c1, Long c2, Long c3) {
        this.network = network;
        this.c1 = c1;
        this.c2 = c2;
        this.c3 = c3;
    }
}
