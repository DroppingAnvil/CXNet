/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.nodemesh.PeerDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * Seeds are versioned and stored both locally (seeds/ directory) and on-chain (c2 Resources chain).
 *
 * Peers are stored ONLY as signed blobs (same format as CXHELLO signedNode).
 * Each blob is signed by the peer's own key and verified before ingestion.
 */
public class Seed {
    private static final Logger log = LoggerFactory.getLogger(Seed.class);

    private static final String OFFICIAL_SEED_URL = "https://anvildevelopment.us/downloads/cxnet-bootstrap.cxn";

    /**
     * Unique identifier for this seed version
     */
    public String seedID;

    /**
     * Timestamp when this seed was created (epoch milliseconds)
     */
    public long timestamp;

    /**
     * Network ID this seed is for (e.g., "CXNET")
     */
    public String networkID;

    /**
     * High-value peer blobs for initial connection.
     * Each entry is a signed Node blob -- the node signed its own entry (same as CXHELLO signedNode).
     * Verified against the node's own embedded public key before ingestion.
     * LAN peers are NEVER included.
     */
    public List<byte[]> hvPeerBlobs;

    /**
     * Peer-finding node blobs -- subset of hvPeerBlobs that are good for peer discovery.
     */
    public List<byte[]> peerFindingNodeBlobs;

    /**
     * List of network configurations to import
     */
    public List<CXNetwork> networks;

    /**
     * Map of cxID -> PGP public key certificate for non-node keys (e.g., NMI keys)
     */
    public Map<String, String> certificates;

    public Seed() {
        this.hvPeerBlobs = new ArrayList<>();
        this.peerFindingNodeBlobs = new ArrayList<>();
        this.networks = new ArrayList<>();
        this.certificates = new HashMap<>();
    }

    /**
     * Add a high-value peer signed blob to the seed.
     * @param signedBlob Node blob signed by the peer's own key (must not be LAN peer)
     */
    public void addHvPeer(byte[] signedBlob) {
        if (signedBlob != null) {
            hvPeerBlobs.add(signedBlob);
        }
    }

    /**
     * Add a peer-finding node blob (also adds to hvPeerBlobs).
     * @param signedBlob Node blob signed by the peer's own key
     */
    public void addPeerFindingNode(byte[] signedBlob) {
        if (signedBlob != null) {
            peerFindingNodeBlobs.add(signedBlob);
            hvPeerBlobs.add(signedBlob);
        }
    }

    /**
     * Add a network to the seed.
     */
    public void addNetwork(CXNetwork network) {
        if (network != null) {
            networks.add(network);
        }
    }

    /**
     * Add a certificate to the seed (for non-node NMI keys).
     */
    public void addCertificate(String cxID, String publicKey) {
        if (cxID != null && publicKey != null) {
            certificates.put(cxID, publicKey);
        }
    }

    /**
     * Create a seed from current PeerDirectory high-value peers.
     * Pulls signed blobs from signedNodeCache -- only peers with signed blobs are included.
     * LAN peers are automatically excluded.
     */
    public static Seed fromCurrentPeers(PeerDirectory peerDirectory) {
        Seed seed = new Seed();
        if (peerDirectory != null && peerDirectory.hv != null) {
            for (String cxID : peerDirectory.hv.keySet()) {
                byte[] signedBlob = peerDirectory.getSignedNode(cxID);
                if (signedBlob != null) {
                    seed.addHvPeer(signedBlob);
                } else {
                    log.debug("[Seed] Skipping peer {} -- no signed blob available", cxID.substring(0, 8));
                }
            }
        }
        return seed;
    }

    /**
     * Save this Seed to a file using ConnectX serialization
     */
    public void save(File file) throws Exception {
        String json = ConnectX.serialize("cxJSON1", this);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(json);
        }
    }

    /**
     * Load a Seed from a file using ConnectX deserialization
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
     * Fetch the official CXNET seed.
     * Tries EPOCH first via ConnectX (if provided), then falls back to
     * the official download at {@value #OFFICIAL_SEED_URL}.
     *
     * @param connectX ConnectX instance for EPOCH request (may be null to use HTTP fallback only)
     * @return Official CXNET seed
     * @throws Exception if all fetch attempts fail
     */
    public static Seed fetchOfficial(ConnectX connectX) throws Exception {
        // Try EPOCH first when a connected instance is available
        if (connectX != null) {
            try {
                log.info("[Seed] Requesting official seed from EPOCH");
                connectX.joinNetworkFromPeers("CXNET");
                // joinNetworkFromPeers is async; caller is expected to wait on networkMap
                // Return null here -- the seed will be applied asynchronously
                return null;
            } catch (Exception e) {
                log.warn("[Seed] EPOCH seed request failed, falling back to HTTP: {}", e.getMessage());
            }
        }

        // Fall back to official HTTP download
        log.info("[Seed] Fetching official seed from {}", OFFICIAL_SEED_URL);
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(OFFICIAL_SEED_URL)
            .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " fetching official seed");
            }
            byte[] body = response.body().bytes();
            String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            Seed seed = (Seed) ConnectX.deserialize("cxJSON1", json, Seed.class);
            log.info("[Seed] Fetched official seed: {} ({} blobs, {} networks)",
                seed.seedID, seed.hvPeerBlobs.size(), seed.networks.size());
            return seed;
        }
    }

    /**
     * Apply this seed to a ConnectX instance
     */
    public void apply(ConnectX connectX) throws Exception {
        log.info("[Seed] Applying seed {}", seedID);
        log.info("[Seed]   Networks: {}", networks.size());
        log.info("[Seed]   HV Peer Blobs: {}", hvPeerBlobs.size());
        log.info("[Seed]   Certificates: {}", certificates.size());

        java.lang.reflect.Method applySeedMethod = ConnectX.class.getDeclaredMethod("applySeed", Seed.class);
        applySeedMethod.setAccessible(true);
        applySeedMethod.invoke(connectX, this);

        log.info("[Seed] Seed application complete");
    }
}
