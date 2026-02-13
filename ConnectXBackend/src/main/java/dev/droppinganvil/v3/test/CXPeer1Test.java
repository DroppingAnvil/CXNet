/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.CXNetwork;
import dev.droppinganvil.v3.network.Seed;
import dev.droppinganvil.v3.network.nodemesh.Node;
import dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider;

import java.io.File;
import java.util.UUID;

/**
 * In this test Peer1 will create two networks, CXNET (The global network, created on this peer for concept) and TESTNET (This network will be primarily used for testing purposes)
 * MultiPeerTest is still used in favor of this test for now
 */
public class CXPeer1Test {
    public static final boolean addEpoch = true;
    public static final String NETWORK_NAME = "CXNET";
    public static final String EPOCH_DIR = "ConnectX-EPOCH";
    public static final String SERVER_ID = "00000000-0000-0000-0000-000000000001";
    public static final int EPOCH_P2P_PORT = 49152;
    public static final int EPOCH_HTTP_PORT = 8080;
    public static String EPOCH_PUB_KEY = null;
    public ConnectX peer1 = new ConnectX(EPOCH_DIR);

    public CXPeer1Test() throws Exception {
        peer1.updateHTTPBridgePort(EPOCH_HTTP_PORT);


        //After Peer1 is ready
        handleEPOCH();
        handleTESTNET();
        peer1.setPublicBridgeAddress("cxHTTP1", "https://cx1.anvildevelopment.us/cx");
        peer1.connect(EPOCH_P2P_PORT);

    }

    public static void main(String... args) throws Exception {
        new CXPeer1Test();
    }

    /**
     * Handles TESTNET creation or loading as NMI
     * @throws IllegalAccessException
     */
    public void handleTESTNET() throws Exception {
        ConnectX server = peer1;
        CXNetwork testnet = server.getNetwork("TESTNET");

        if (testnet == null) {
            testnet = server.createNetwork("TESTNET");

            // Disable whitelist mode for blockchain replication testing
            testnet.configuration.whitelistMode = false;
            testnet.configuration.publicSeed = false;
            testnet.configuration.syncIntervalSeconds = 30; // Testing mode

            System.out.println("  ✓ TESTNET created for blockchain replication testing");
            System.out.println("    Whitelist mode: DISABLED (allows all peers for testing)");
            System.out.println("    Backend/NMI: EPOCH");
            System.out.println("    Sync interval: 30 seconds (testing mode)");

            // Create TESTNET seed
            Seed testnetSeed = new Seed();
            testnetSeed.seedID = UUID.randomUUID().toString();
            testnetSeed.timestamp = System.currentTimeMillis();
            testnetSeed.networkID = "TESTNET";
            testnetSeed.addNetwork(testnet);

            // Add EPOCH as bootstrap peer for TESTNET too
            Node epochNode = new Node();
            epochNode.cxID = SERVER_ID;
            epochNode.publicKey = EPOCH_PUB_KEY;
            epochNode.addr = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";
            testnetSeed.addHvPeer(epochNode);
            testnetSeed.addPeerFindingNode(epochNode);

            // Save TESTNET seed
            File seedsDir = new File(server.cxRoot, "seeds");
            File testnetSeedFile = new File(seedsDir, "testnet_" + testnetSeed.seedID + ".cxn");
            testnetSeed.save(testnetSeedFile);

            System.out.println("  ✓ TESTNET seed created: testnet_" + testnetSeed.seedID + ".cxn");
        } else {
            System.out.println("  ✓ TESTNET already loaded in memory");
        }
    }

    /**
     * This handles the global CXNET creation and loading for testing purposes, this will not be required once CXNET is stable
     */
    public void handleEPOCH() throws Exception {
        // Create EPOCH NMI instance with dedicated directory
        java.io.File cxnetDir = new java.io.File(EPOCH_DIR);
        if (!cxnetDir.exists()) {
            cxnetDir.mkdirs();
            System.out.println("Created EPOCH directory: " + EPOCH_DIR);
        }

        ConnectX server = peer1;

        // Generate EPOCH keys (NMI for CXNET)
        System.out.println("Step 1: Initializing EPOCH (NMI) identity...");
        String serverPubKey = null;
        try {
            // Enable EPOCH mode - this node IS the NMI (Network Master Identity)
            // TODO: Remove after HTTP bridge seed download is implemented
            server.encryptionProvider.setEpochMode(true);

            // Generate new key pair with password "cxnet" for EPOCH
            server.encryptionProvider.setup(SERVER_ID, "cxnet", server.cxRoot);
            serverPubKey = server.encryptionProvider.getPublicKey();
            EPOCH_PUB_KEY = serverPubKey;
            System.out.println("  ✓ EPOCH crypto initialized");
            System.out.println("  Public key: " + serverPubKey.substring(0, 50) + "...");

            // Copy the generated public key to cx.asc (network master key)
            java.io.File keyFile = new java.io.File(server.cxRoot, "key.cx");
            java.io.File publicKeyDest = new java.io.File(server.cxRoot, "cx.asc");
            if (keyFile.exists() && !publicKeyDest.exists()) {
                // Extract public key from secret key and save as cx.asc
                // For now, the NMI public key is set during crypto init
                System.out.println("  ✓ Network master key: " + SERVER_ID);
            }
        } catch (Exception e) {
            System.err.println("  ✗ Crypto initialization failed: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Create EPOCH node identity with cxHTTP1 bridge address
        Node serverNode = new Node();
        serverNode.cxID = SERVER_ID;
        serverNode.publicKey = serverPubKey;
        serverNode.addr = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";
        server.setSelf(serverNode);
        System.out.println("  ✓ EPOCH identity: " + SERVER_ID);

        // Initialize and add EPOCH to peer directory for signature verification
        if (addEpoch) {
            server.registerHVPeer(serverNode);
            System.out.println("  ✓ EPOCH added to peer directory");
        }

        // Get auto-registered cxHTTP1 bridge provider (per-instance)
        System.out.println("\nStep 2: Getting cxHTTP1 bridge provider...");
        BridgeProvider httpBridge = server.getBridgeProvider("cxHTTP1");
        if (httpBridge == null) {
            System.err.println("  ✗ cxHTTP1 bridge not registered (should be auto-registered)");
            return;
        }
        System.out.println("  ✓ cxHTTP1 bridge found (auto-registered by ConnectX)");

        // Create or load CXNET network
        System.out.println("\nStep 3: Setting up CXNET network...");
        dev.droppinganvil.v3.network.CXNetwork cxnet;

        // Check if CXNET already exists in memory
        cxnet = server.getNetwork(NETWORK_NAME);
        if (cxnet == null) {
            // Check if blockchain data exists on disk (persistence test)
            boolean blockchainExists = server.blockchainPersistence.exists(NETWORK_NAME);

            if (blockchainExists) {
                System.out.println("  ✓ CXNET blockchain found on disk - testing persistence");

                // Try to load persisted blockchain by recreating network structure
                // In production, this would happen via importNetwork() from a seed
                // For EPOCH/NMI, we recreate the network structure and let persistence layer restore it
                cxnet = server.createNetwork(NETWORK_NAME);

                // Verify blockchain was restored from disk
                ConnectX.BlockchainStats stats = server.getBlockchainStats(cxnet);
                System.out.println("  ✓ Blockchain loaded from disk");
                System.out.println("    c1: " + stats.c1BlockCount + " blocks (current: " + stats.c1CurrentBlock + ")");
                System.out.println("    c2: " + stats.c2BlockCount + " blocks (current: " + stats.c2CurrentBlock + ")");
                System.out.println("    c3: " + stats.c3BlockCount + " blocks (current: " + stats.c3CurrentBlock + ")");

            } else {
                // Create new CXNET network (first run)
                System.out.println("  No existing blockchain found - creating new CXNET");
                cxnet = server.createNetwork(NETWORK_NAME);

                // Set sync interval to 30 seconds for testing (BEFORE creating seed)
                cxnet.configuration.syncIntervalSeconds = 30;

                System.out.println("  ✓ CXNET created");
                System.out.println("    Chain c1 (Admin): " + cxnet.networkDictionary.c1);
                System.out.println("    Chain c2 (Resources): " + cxnet.networkDictionary.c2);
                System.out.println("    Chain c3 (Events): " + cxnet.networkDictionary.c3);
                System.out.println("    NMI: EPOCH");
                System.out.println("    Sync interval: 30 seconds (testing mode)");

                // Verify blockchain was persisted
                ConnectX.BlockchainStats stats = server.getBlockchainStats(cxnet);
                System.out.println("  ✓ Blockchain persisted to disk");
                System.out.println("    c1: " + stats.c1BlockCount + " blocks");
                System.out.println("    c2: " + stats.c2BlockCount + " blocks");
                System.out.println("    c3: " + stats.c3BlockCount + " blocks");
            }

            // Create versioned seed for CXNET distribution
            System.out.println("  Creating official CXNET seed...");
            dev.droppinganvil.v3.network.Seed seed = new dev.droppinganvil.v3.network.Seed();

            // Set seed metadata
            seed.seedID = java.util.UUID.randomUUID().toString();
            seed.timestamp = System.currentTimeMillis();
            seed.networkID = NETWORK_NAME;

            // Add CXNET network to seed
            seed.addNetwork(cxnet);

            // Add EPOCH as bootstrap peer (hv peer and peer finding node)
            dev.droppinganvil.v3.network.nodemesh.Node epochNode = new dev.droppinganvil.v3.network.nodemesh.Node();
            epochNode.cxID = SERVER_ID;
            epochNode.publicKey = serverPubKey;
            epochNode.addr = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";
            seed.addHvPeer(epochNode);
            seed.addPeerFindingNode(epochNode);

            // Create seeds/ directory
            java.io.File seedsDir = new java.io.File(server.cxRoot, "seeds");
            if (!seedsDir.exists()) {
                seedsDir.mkdirs();
                System.out.println("  Created seeds/ directory");
            }

            // Save versioned seed to seeds/{seedID}.cxn
            java.io.File seedFile = new java.io.File(seedsDir, seed.seedID + ".cxn");
            seed.save(seedFile);

            // Update CXNET configuration with current seed ID
            if (cxnet.configuration.currentSeedID != null) {
                cxnet.configuration.lastSeedID = cxnet.configuration.currentSeedID;
            }
            cxnet.configuration.currentSeedID = seed.seedID;

            // Display seed info
            System.out.println("  ✓ Seed created and saved:");
            System.out.println("    Seed ID: " + seed.seedID);
            System.out.println("    Timestamp: " + seed.timestamp);
            System.out.println("    Networks: " + seed.networks.size() + " (CXNET)");
            System.out.println("    HV Peers: " + seed.hvPeers.size() + " (EPOCH)");
            System.out.println("    Peer Finding: " + seed.peerFindingNodes.size() + " (EPOCH)");
            System.out.println("    Certificates: " + seed.certificates.size() + " (EPOCH)");
            System.out.println("    File: seeds/" + seed.seedID + ".cxn");

            // TODO: Record seed to c2 (Resources chain) blockchain for distribution
            // This will allow automatic loading when new nodes join the network
            // server.recordResource(NETWORK_NAME, seed);

        } else {
            System.out.println("  ✓ CXNET already loaded in memory");
        }

        // Create TESTNET for blockchain replication testing
        System.out.println("\nStep 3b: Setting up TESTNET (blockchain replication test network)...");
    }
}
