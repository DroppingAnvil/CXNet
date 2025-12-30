/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.events;

import java.util.List;

public class PeerFinding {
    /*
    List of current peers 30% of current connections up to 5
    Node ID, SIGNED NODE DATA FROM ORIGIN (node that created Node object)
     */
    public byte[] currentPeers;
    /*
    LIST of IP/Socket/Bridges that could be connected to 30% max up to 20
     */
    public byte[] peers;
    /*
    If this field is included it should indicate that a peer is bootstrapping/peerfind a specific network
    Randomly select up to 50 nodes from that network and include them all SIGNED FROM ORIGIN
     */
    public String network;
    /*
    This is where the list of up to 50 nodes will go
    Uses byte[] to match InputBundle/NetworkContainer pattern for signed crypto objects
     */
    public List<byte[]> signedNodes;
    public String t;

    public PeerFinding() {}
}
