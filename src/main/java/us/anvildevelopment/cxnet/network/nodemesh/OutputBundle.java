/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import us.anvildevelopment.cxnet.network.events.NetworkContainer;
import us.anvildevelopment.cxnet.network.events.NetworkEvent;

public class OutputBundle {
    public NetworkEvent ne;
    public Node n;
    public String s;
    public byte[] prev;
    public NetworkContainer nc;
    /**
     * Low-level direct transmission flag.
     * When true, bypasses CXS/CXN scope routing and sends directly to out.n.addr.
     * Use for pre-discovery events (CXHELLO) where peer ID is not yet known.
     */
    public boolean ll;

    public OutputBundle(NetworkEvent ne, Node n, String s, byte[] prev, NetworkContainer nc) {
        this.ne = ne;
        this.n = n;
        this.s = s;
        this.prev = prev;
        this.nc = nc;
        if (this.nc==null) this.nc = new NetworkContainer();
    }
}
