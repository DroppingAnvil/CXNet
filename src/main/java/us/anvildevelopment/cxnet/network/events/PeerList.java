/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

import java.util.ArrayList;
import java.util.List;

/** Payload for PEER_LIST_REQUEST and PEER_LIST_RESPONSE events. */
public class PeerList {
    /** IP:port addresses of known peers. Populated in the response. */
    public List<String> ips = new ArrayList<>();

    public PeerList() {}
}