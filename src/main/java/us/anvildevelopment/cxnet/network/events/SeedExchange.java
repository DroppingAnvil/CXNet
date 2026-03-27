/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

import us.anvildevelopment.cxnet.network.Seed;

/**
 * Payload for SEED_REQUEST and SEED_RESPONSE events.
 * One class serves both event types. Direction is determined solely by the
 * EventType constant on the NetworkEvent, not by the presence or absence of any field.
 * Fields not relevant to a given event type are left null.
 *
 * SEED_REQUEST: only {@code network} is set.
 * SEED_RESPONSE: all remaining fields are set; {@code network} identifies the subject network.
 */
public class SeedExchange {
    /** Network ID the request or response applies to */
    public String network;
    /** Current dynamic seed built from live peer state. Response only. */
    public Seed dynamicSeed;
    /** Signed epoch seed loaded from disk, if available. Response only. */
    public Seed epochSeed;
    /** True if the responding peer is an authoritative NMI/backend node. Response only. */
    public Boolean authoritative;
    /** ID of the responding peer. Response only. */
    public String senderID;
    /** Chain 1 block height at time of response. Response only. */
    public Long c1;
    /** Chain 2 block height at time of response. Response only. */
    public Long c2;
    /** Chain 3 block height at time of response. Response only. */
    public Long c3;

    public SeedExchange() {}

    /** Convenience constructor for a SEED_REQUEST payload. */
    public SeedExchange(String network) {
        this.network = network;
    }
}
