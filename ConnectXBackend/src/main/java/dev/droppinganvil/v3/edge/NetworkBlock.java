/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.edge;

import dev.droppinganvil.v3.network.events.NetworkEvent;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkBlock implements Serializable {
    public Long block;
    public Map<Integer, NetworkEvent> networkEvents;

    // Default constructor for Jackson deserialization
    public NetworkBlock() {
        networkEvents = new ConcurrentHashMap<>();
    }

    public NetworkBlock(Long block) {
        this.block = block;
        networkEvents = new ConcurrentHashMap<>();
    }
}
