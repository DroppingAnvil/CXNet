package dev.droppinganvil.v3.network.events;


import dev.droppinganvil.v3.network.CXPath;

import java.io.Serializable;

public class NetworkEvent implements Serializable {

    // Default constructor for Jackson deserialization
    public NetworkEvent() {
    }

    public NetworkEvent(EventType type, byte[] d) {
        this.eT = type.name();
        this.d = d;
    }

    public String eT;
    public CXPath p;
    public String iD;
    /**
     * Method for processing
     */
    public String m;
    /**
     * Event specific data
     */
    public byte[] d;
}
