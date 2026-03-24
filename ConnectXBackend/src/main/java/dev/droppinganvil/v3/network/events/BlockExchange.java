/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.events;

/**
 * Payload for BLOCK_REQUEST and BLOCK_RESPONSE events.
 * One class serves both event types. Direction is determined solely by the
 * EventType constant on the NetworkEvent, not by the presence or absence of any field.
 * Fields not relevant to a given event type are left null.
 *
 * BLOCK_REQUEST: {@code network}, {@code chain}, and {@code block} identify the requested block.
 * BLOCK_RESPONSE: all fields are set; {@code blockData} carries the full block.
 */
public class BlockExchange {
    /** Network ID the block belongs to */
    public String network;
    /** Chain ID (c1, c2, or c3 numeric ID from the network dictionary) */
    public Long chain;
    /** Block number being requested or returned */
    public Long block;
    /** Full block data. Response only. */
    public dev.droppinganvil.v3.edge.NetworkBlock blockData;

    public BlockExchange() {}

    /** Convenience constructor for a BLOCK_REQUEST payload. */
    public BlockExchange(String network, Long chain, Long block) {
        this.network = network;
        this.chain = chain;
        this.block = block;
    }
}
