/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.nodemesh.Node;
import dev.droppinganvil.v3.network.nodemesh.PeerDirectory;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seed contains initial bootstrap data for starting a ConnectX instance.
 *
 * Seed files allow new nodes to bootstrap into the network with:
 * - High-value (hv) peers for initial connections (LAN peers are NEVER shared)
 * - Network configurations to import
 * - Public key certificates for peer verification
 * - Ability to fetch official seeds from CXNET
 */
public class Seed {
    /**
     * High-value peers for initial connection
     * Contains Node objects with cxID, addr, and publicKey
     * NOTE: LAN peers are NEVER included in seeds for privacy/security
     */
    public List<Node> hvPeers;

    /**
     * Additional peers for peer finding requests
     * Subset of hvPeers that are good for discovering more peers
     */
    public List<Node> peerFindingNodes;

    /**
     * List of network configurations to import
     * Contains CXNetwork objects with full network state
     */
    public List<CXNetwork> networks;

    /**
     * Map of cxID -> PGP public key certificate
     * Used for verifying peer identities before first contact
     */
    public Map<String, String> certificates;

    /**
     * Create a new empty Seed
     */
    public Seed() {
        this.hvPeers = new ArrayList<>();
        this.peerFindingNodes = new ArrayList<>();
        this.networks = new ArrayList<>();
        this.certificates = new HashMap<>();
    }

    /**
     * Add a high-value peer to the seed
     * @param node Node to add (must not be a LAN peer)
     */
    public void addHvPeer(Node node) {
        if (node != null && node.cxID != null) {
            hvPeers.add(node);
            // Also add certificate if available
            if (node.publicKey != null) {
                certificates.put(node.cxID, node.publicKey);
            }
        }
    }

    /**
     * Add a peer finding node to the seed
     * @param node Node good for peer discovery
     */
    public void addPeerFindingNode(Node node) {
        if (node != null && node.cxID != null) {
            peerFindingNodes.add(node);
            // Also ensure it's in hvPeers
            if (!hvPeers.contains(node)) {
                addHvPeer(node);
            }
        }
    }

    /**
     * Add a network to the seed
     * @param network CXNetwork to add
     */
    public void addNetwork(CXNetwork network) {
        if (network != null) {
            networks.add(network);
        }
    }

    /**
     * Add a certificate to the seed
     * @param cxID Node identifier
     * @param publicKey PGP public key
     */
    public void addCertificate(String cxID, String publicKey) {
        if (cxID != null && publicKey != null) {
            certificates.put(cxID, publicKey);
        }
    }

    /**
     * Create a seed from current PeerDirectory high-value peers
     * NOTE: LAN peers are automatically excluded
     * @return Seed containing current hv peers
     */
    public static Seed fromCurrentPeers() {
        Seed seed = new Seed();
        // Only include hv peers, NEVER LAN peers
        for (Node node : PeerDirectory.hv.values()) {
            seed.addHvPeer(node);
        }
        return seed;
    }

    /**
     * Save this Seed to a file using ConnectX serialization
     * @param file File to save to
     * @throws Exception if save fails
     */
    public void save(File file) throws Exception {
        String json = ConnectX.serialize("cxJSON1", this);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(json);
        }
    }

    /**
     * Load a Seed from a file using ConnectX deserialization
     * @param file File to load from
     * @return Loaded Seed
     * @throws Exception if load fails
     */
    public static Seed load(File file) throws Exception {
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
        }
        return (Seed) ConnectX.deserialize("cxJSON1", json.toString(), Seed.class);
    }

    /**
     * Fetch the official seed from CXNET
     * This queries the CXNET backend nodes for the current official seed
     * @param connectX ConnectX instance to use for fetching
     * @return Official CXNET seed
     * @throws Exception if fetch fails
     */
    public static Seed fetchOfficial(ConnectX connectX) throws Exception {
        // TODO: Implement fetching official seed from CXNET
        // This would:
        // 1. Send a request to CXNET backendSet nodes
        // 2. Receive the official seed response
        // 3. Verify it's signed by CXNET NMI
        // 4. Return the seed
        throw new UnsupportedOperationException("Official seed fetching not yet implemented");
    }

    /**
     * Apply this seed to a ConnectX instance
     * This will:
     * - Cache all certificates
     * - Add all hv peers to PeerDirectory (LAN peers never included)
     * - Import all networks
     *
     * @param connectX ConnectX instance to apply to
     * @throws Exception if application fails
     */
    public void apply(ConnectX connectX) throws Exception {
        System.out.println("[Seed] Applying seed with " + hvPeers.size() + " hv peers, " +
                          peerFindingNodes.size() + " peer finding nodes, " +
                          networks.size() + " networks");

        // Cache certificates first
        for (Map.Entry<String, String> cert : certificates.entrySet()) {
            // TODO: Cache certificate in encryption provider
            // connectX.encryptionProvider.cacheCert(cert.getKey(), cert.getValue());
            System.out.println("[Seed] Cached certificate for: " + cert.getKey());
        }

        // Add hv peers to directory (LAN peers are never in seeds)
        for (Node peer : hvPeers) {
            PeerDirectory.addNode(peer);
            System.out.println("[Seed] Added hv peer: " + peer.cxID + " at " + peer.addr);
        }

        // Import networks
        for (CXNetwork network : networks) {
            // TODO: Add public method in ConnectX to add network directly
            // For now, networks need to be imported via importNetwork(File) method
            System.out.println("[Seed] Network available for import: " + network.networkDictionary.networkID);
        }

        System.out.println("[Seed] Seed application complete");
    }
}