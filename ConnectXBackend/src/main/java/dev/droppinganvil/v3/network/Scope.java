/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network;

public enum Scope {
    CX,
    /**
     * ConnectX System (Device)
     * This is used for most communication where network is irrelevant such as CXHELLO
     * These messages will default with event builder to only being received by the one peer they were sent to, and not relayed (DEFAULT)
     */
    CXS,
    /**
     * ConnectX Network scope
     * This is used for 90% of interactions through CX
     * This is the only scope where blockchain recording can occur, the rational is that if peers individually got blockchain data there would be mass fragmentation
     * This scope will default to sending event to ALL available peers, with the transmission method of success being used and then continue to next peer, this is different to how CX scope transmits to ALL peer addresses for ALL peers
     */
    CXN,
    /**
     * ConnectX Resource scope
     * This is used by Remote Directory a resource management core for CX
     */
    CXR,
    /**
     * LAN is a scope for direct CXS messages in a lower level CX network comprised of "local" cx devices
     * The idea here is that users could bind their 2 computers, phone...  together on a secure lan for personal networking
     */
    LAN,
}
