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
    /**
     * Determines if event should be executed during blockchain sync
     * TRUE = State-modifying events (permission changes, NMI updates) - MUST execute during sync
     * FALSE = Ephemeral/realtime events (messages, pings) - only execute when received live
     *
     * Example: A 2-year-old permission change MUST be applied during sync to rebuild state
     *          A 2-year-old message should NOT be displayed during sync
     *
     * Default: false (most events are realtime)
     */
    public boolean executeOnSync = false;
}
