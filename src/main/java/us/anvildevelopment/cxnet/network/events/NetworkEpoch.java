/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.events;

/**
 * Payload for NETEPOCH and NETEPOCH_RESPONSE events.
 *
 * NETEPOCH (creator to CXNET NMI):
 *   - {@code networkID}     the network being registered
 *   - {@code cxik}          one-time integration key issued by the CXNET NMI
 *   - {@code signedSeedBlob} seed for the new network signed by the creator's key
 *
 * NETEPOCH_RESPONSE (CXNET NMI to creator):
 *   - {@code networkID}     the network that was processed
 *   - {@code success}       true if the key was valid and the network was imported
 *   - {@code message}       human-readable status or error description
 *   - {@code signedSeedBlob} (success only) NMI-signed seed for the imported network
 */
public class NetworkEpoch {
    public String networkID;
    public String cxik;
    public byte[] signedSeedBlob;
    public boolean success;
    public String message;

    public NetworkEpoch() {}
}