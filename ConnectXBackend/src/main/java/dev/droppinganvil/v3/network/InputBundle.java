/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.events.NetworkEvent;

import java.io.ByteArrayInputStream;

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
     * Internal event payload, if verified it will be stripped and placed here for deserialization
     */
    public byte[] verifiedObjectBytes;
    /**
     * Stripped and Deserialized Object if available
     */
    public Object object;


    public void verifyCryptoLayers() {

    }
    public void verifyCryptoLayersE2E() {

    }

    /**
     * Make InputBundle.object ready, as the class is per use case it cannot be abstracted that far and must be handled specially by handlers
     * @param clazz Object class
     * @param serializationMethod Serialization method ex cxJSON1
     * @return success
     */
    public boolean readyObject(Class<?> clazz, String serializationMethod, ConnectX connectX) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(verifiedObjectBytes);
        Object o1 = connectX.deserialize(serializationMethod, bais, clazz);
        if (o1 != null) {
            object = o1;
            return true;
        } else {
            return false;
        }
    }

    public boolean verify() {
        return false;
    }
}
