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

    /**
     * Record flag - determines if event should be recorded to blockchain when received
     * TRUE = Event will be recorded to blockchain if sender has Record permission
     * FALSE = Event is ephemeral and will not be recorded
     *
     * Default: false (most events are not recorded)
     */
    public boolean r = false;
    /**
     * This flag identifies if the inner data NetworkEvent.d will be signed or encrypted
     * False: NodeMesh will treat the inner data as signed event data and will check signatures then attempt deserialization into desired object type
     * True: NodeMesh will treat the inner data as E2E encrypted, and will attempt to decrypt it into desired object, but if it cannot due to cryptographic access control, or due to it being out of scope NodeMesh will use TransmitPref from NetworkContainer to determine routing/proxy
     */
    public boolean e2e = false;
}
