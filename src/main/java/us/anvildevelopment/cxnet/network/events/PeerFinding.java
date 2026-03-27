/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

import java.util.ArrayList;
import java.util.List;

public class PeerFinding {
    /**
     * If requesting a specific nodes data, Ex Peer1 knows of Peer2 CXID but they have not met and Peer2 is unreachable, Peer1 can ask peers for Peer2 Node blob
     */
    public String rID = "";
    /*
    List of current peers 30% of current connections up to 5
    Node ID, SIGNED NODE DATA FROM ORIGIN (node that created Node object)
     */
    public byte[] currentPeers;
    /*
    LIST of ALL IP/Socket/Bridges that could be connected to 30% max up to 20
    //TODO security eval, implement local security modes?
     */
    public List<String> peers = new ArrayList<>();
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
    /*
    Raw IP addresses shared during peer discovery, distinct from bridge/socket addresses in peers
     */
    public List<String> ips = new ArrayList<>();
    public String t;

    public PeerFinding() {}
}
