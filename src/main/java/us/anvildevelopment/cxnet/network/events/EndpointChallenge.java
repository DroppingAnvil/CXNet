/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

/**
 * Payload for ENDPOINT_CHALLENGE and ENDPOINT_PROOF.
 *
 * The challenge carries a nonce and the address being validated; the proof echoes both and names
 * the responder. The address is included so a nonce is bound to the one endpoint it was issued
 * for: without it, a proof obtained for one of a peer's addresses could be presented as evidence
 * for another.
 */
public class EndpointChallenge {

    /** Random single-use nonce, hex encoded. */
    public String nonce;

    /**
     * The advertised address under test, exactly as it appears in the peer's advertisement.
     * Echoed back in the proof so the verifier can confirm which endpoint was proven.
     */
    public String address;

    /** The cxID being challenged. Echoed in the proof, and compared against the actual signer. */
    public String target;

    /** The responder's own cxID, set on the proof. */
    public String responder;

    /** When the challenge was issued, epoch millis. Used to expire stale nonces. */
    public long issuedAt;

    public EndpointChallenge() {}

    public EndpointChallenge(String nonce, String address, String target) {
        this.nonce = nonce;
        this.address = address;
        this.target = target;
        this.issuedAt = System.currentTimeMillis();
    }
}
