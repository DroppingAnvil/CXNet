/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

/**
 * Wire format for PEER_LOOKUP_REQUEST / PEER_LOOKUP_RESPONSE.
 *
 * Used to bootstrap trust for a peer this node has not met directly. Any node holding a
 * cosigned identity for targetCXID may answer, the response is trusted by verifying the
 * outer signature against EPOCH or a CXNET backendSet member, not by trusting the responder.
 *
 * Wire structure of cosignedBlob: {signedByTrusted{signedByPeer{Node}}}
 *   - Outer layer: signed by EPOCH or a CXNET backendSet member
 *   - Inner layer: the target's own self-signed Node blob, unmodified, including Node.signedAt
 *
 * Request: targetCXID set, cosignedBlob null.
 * Response: targetCXID echoed, cosignedBlob set if the responder has one, otherwise no response is sent.
 */
public class PeerLookup {
    public String targetCXID;
    public byte[] cosignedBlob;

    public PeerLookup() {}

    public PeerLookup(String targetCXID) {
        this.targetCXID = targetCXID;
    }
}
