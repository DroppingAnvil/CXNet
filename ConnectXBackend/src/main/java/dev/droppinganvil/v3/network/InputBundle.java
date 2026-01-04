/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network;

import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.events.NetworkEvent;

public class InputBundle {

    public InputBundle(NetworkEvent ne, NetworkContainer nc) {
        this.ne = ne;
        this.nc = nc;
        // Preserve the signed NetworkEvent bytes from nc.e for relaying
        this.signedEventBytes = (nc != null) ? nc.e : null;
    }

    public NetworkEvent ne;
    public NetworkContainer nc;
    /**
     * Signed NetworkEvent bytes (nc.e) - preserved for relay
     * Must NOT be re-signed during relay to maintain original sender's signature
     */
    public byte[] signedEventBytes;
    /**
     * New method to streamline NodeMesh operations, used by some functions, not fully implemented yet
     */
    public byte[] strippedEventBytes;
    /**
     * Stripped and Deserialized Object if available
     */
    public Object object;


    public void verifyCryptoLayers() {

    }
    public void verifyCryptoLayersE2E() {

    }

    public boolean verify() {
        return false;
    }
}
