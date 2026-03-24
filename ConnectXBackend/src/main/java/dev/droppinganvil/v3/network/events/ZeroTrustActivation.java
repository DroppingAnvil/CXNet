/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.events;

/**
 * Payload for ZERO_TRUST_ACTIVATION events.
 * Sent by the NMI to permanently activate zero trust mode on a network.
 * This operation is irreversible once applied.
 */
public class ZeroTrustActivation {
    /** Network ID to activate zero trust mode on */
    public String network;
    /** Always true; carried for clarity and chain replay */
    public Boolean zT;
    /** Timestamp of activation */
    public Long timestamp;
    /** NMI public key at time of activation */
    public String nmi;

    public ZeroTrustActivation() {}

    public ZeroTrustActivation(String network, Long timestamp, String nmi) {
        this.network = network;
        this.zT = true;
        this.timestamp = timestamp;
        this.nmi = nmi;
    }
}
