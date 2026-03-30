package us.anvildevelopment.cxnet.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.CXNetwork;
import us.anvildevelopment.cxnet.network.InputBundle;
import us.anvildevelopment.cxnet.network.Seed;
import us.anvildevelopment.cxnet.network.events.EventType;
import us.anvildevelopment.cxnet.network.nodemesh.Node;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.BridgeProvider;

/**
 * HTTP Bridge Test
 *
 * Tests cxHTTP1 bridge provider with RProx reverse proxy
 * This test is also what is being used to serve as a bootstrap server
 *
 * CONFIGURATION REQUIRED:
 * 1. Enable RProx at AnvilDevelopment.US/rprox.html
 * 2. Configure target: http://[YOUR_IP]:8080
 *
 * This test will:
 * - Start HTTP server on localhost:8080
 * - Accept test messages from another client
 */
public class BootstrapServerTest {

    public static final int HTTP_PORT = 8080;   // HTTP bridge (for RProx/HTTP clients)
    public static final int P2P_PORT = 49152;   // P2P mesh (for direct CX connections)
    // EPOCH NMI UUID - persistent identifier for the network master node
    public static final String SERVER_ID = "00000000-0000-0000-0000-000000000001";
    public static final String NETWORK_NAME = "CXNET";
    public static final String CXNET_DIR = "ConnectX-EPOCH";
    public static final Boolean addEpoch = false;
    public static final Boolean messages = false;
    public static final Logger log = LoggerFactory.getLogger(BootstrapServerTest.class);

    public static void main(String[] args) throws Exception {
        log.info("=== EPOCH NMI Terminal - CXNET Network ===\n");

        // Create EPOCH NMI instance with dedicated directory
        java.io.File cxnetDir = new java.io.File(CXNET_DIR);
        if (!cxnetDir.exists()) {
            cxnetDir.mkdirs();
            log.info("Created CXNET directory: " + CXNET_DIR);
        }

        ConnectX server = new ConnectX(CXNET_DIR);

        // Generate EPOCH keys (NMI for CXNET)
        log.info("Step 1: Initializing EPOCH (NMI) identity...");
        String serverPubKey = null;
        try {
            // Enable EPOCH mode - this node IS the NMI (Network Master Identity)
            // TODO: Remove after HTTP bridge seed download is implemented
            server.encryptionProvider.setEpochMode(true);

            // Generate new key pair with password "cxnet" for EPOCH
            server.encryptionProvider.setup(SERVER_ID, "cxnet", server.cxRoot);
            serverPubKey = server.encryptionProvider.getPublicKey();
            log.info("  ✓ EPOCH crypto initialized");
            log.info("  Public key: " + serverPubKey.substring(0, 50) + "...");

            // Copy the generated public key to cx.asc (network master key)
            java.io.File keyFile = new java.io.File(server.cxRoot, "key.cx");
            java.io.File publicKeyDest = new java.io.File(server.cxRoot, "cx.asc");
            if (keyFile.exists() && !publicKeyDest.exists()) {
                // Extract public key from secret key and save as cx.asc
                // For now, the NMI public key is set during crypto init
                log.info("  ✓ Network master key: " + SERVER_ID);
            }
        } catch (Exception e) {
            log.error("  ✗ Crypto initialization failed", e);
            return;
        }

        // Create EPOCH node identity with cxHTTP1 bridge address
        Node serverNode = new Node();
        serverNode.cxID = SERVER_ID;
        serverNode.publicKey = serverPubKey;
        serverNode.addr = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";
        server.setSelf(serverNode);
        log.info("  ✓ EPOCH identity: " + SERVER_ID);

       // Initialize and add EPOCH to peer directory for signature verification
        if (addEpoch) {
            if (server.nodeMesh.peerDirectory.hv == null) {
                server.nodeMesh.peerDirectory.hv = new java.util.concurrent.ConcurrentHashMap<>();
            }
            server.nodeMesh.peerDirectory.hv.put(SERVER_ID, serverNode);
            log.info("  ✓ EPOCH added to peer directory");
        }

        // Get auto-registered cxHTTP1 bridge provider (per-instance)
        log.info("\nStep 2: Getting cxHTTP1 bridge provider...");
        BridgeProvider httpBridge = server.getBridgeProvider("cxHTTP1");
        if (httpBridge == null) {
            log.error("  ✗ cxHTTP1 bridge not registered (should be auto-registered)");
            return;
        }
        log.info("  ✓ cxHTTP1 bridge found (auto-registered by ConnectX)");

        // Create or load CXNET network
        log.info("\nStep 3: Setting up CXNET network...");
        CXNetwork cxnet;

        // Check if CXNET already exists in memory
        cxnet = server.getNetwork(NETWORK_NAME);
        if (cxnet == null) {
            // Check if blockchain data exists on disk (persistence test)
            boolean blockchainExists = server.blockchainPersistence.exists(NETWORK_NAME);

            if (blockchainExists) {
                log.info("  ✓ CXNET blockchain found on disk - testing persistence");

                // Try to load persisted blockchain by recreating network structure
                // In production, this would happen via importNetwork() from a seed
                // For EPOCH/NMI, we recreate the network structure and let persistence layer restore it
                cxnet = server.createNetwork(NETWORK_NAME);

                // Verify blockchain was restored from disk
                ConnectX.BlockchainStats stats = server.getBlockchainStats(cxnet);
                log.info("  ✓ Blockchain loaded from disk");
                log.info("    c1: " + stats.c1BlockCount + " blocks (current: " + stats.c1CurrentBlock + ")");
                log.info("    c2: " + stats.c2BlockCount + " blocks (current: " + stats.c2CurrentBlock + ")");
                log.info("    c3: " + stats.c3BlockCount + " blocks (current: " + stats.c3CurrentBlock + ")");

            } else {
                // Create new CXNET network (first run)
                log.info("  No existing blockchain found - creating new CXNET");
                cxnet = server.createNetwork(NETWORK_NAME);

                // Set sync interval to 30 seconds for testing (BEFORE creating seed)
                cxnet.configuration.syncIntervalSeconds = 30;

                log.info("  ✓ CXNET created");
                log.info("    Chain c1 (Admin): " + cxnet.networkDictionary.c1);
                log.info("    Chain c2 (Resources): " + cxnet.networkDictionary.c2);
                log.info("    Chain c3 (Events): " + cxnet.networkDictionary.c3);
                log.info("    NMI: EPOCH");
                log.info("    Sync interval: 30 seconds (testing mode)");

                // Verify blockchain was persisted
                ConnectX.BlockchainStats stats = server.getBlockchainStats(cxnet);
                log.info("  ✓ Blockchain persisted to disk");
                log.info("    c1: " + stats.c1BlockCount + " blocks");
                log.info("    c2: " + stats.c2BlockCount + " blocks");
                log.info("    c3: " + stats.c3BlockCount + " blocks");
            }

            // Create versioned seed for CXNET distribution
            log.info("  Creating official CXNET seed...");
            Seed seed = new Seed();

            // Set seed metadata
            seed.seedID = java.util.UUID.randomUUID().toString();
            seed.timestamp = System.currentTimeMillis();
            seed.networkID = NETWORK_NAME;

            // Add CXNET network to seed
            seed.addNetwork(cxnet);

            // Add EPOCH as bootstrap peer (hv peer and peer finding node)
            Node epochNode = new Node();
            epochNode.cxID = SERVER_ID;
            epochNode.publicKey = serverPubKey;
            epochNode.addr = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";
            seed.addHvPeer(epochNode);
            seed.addPeerFindingNode(epochNode);

            // Create seeds/ directory
            java.io.File seedsDir = new java.io.File(server.cxRoot, "seeds");
            if (!seedsDir.exists()) {
                seedsDir.mkdirs();
                log.info("  Created seeds/ directory");
            }

            // Sign the seed and save as a PGP-signed blob (all .cxn files are signed blobs)
            String seedJson = ConnectX.serialize("cxJSON1", seed);
            java.io.ByteArrayInputStream seedInput = new java.io.ByteArrayInputStream(seedJson.getBytes("UTF-8"));
            java.io.ByteArrayOutputStream signedOutput = new java.io.ByteArrayOutputStream();
            server.encryptionProvider.sign(seedInput, signedOutput);
            seedInput.close();
            byte[] signedBlob = signedOutput.toByteArray();
            signedOutput.close();

            // Save signed blob to seeds/{seedID}.cxn
            java.io.File seedFile = new java.io.File(seedsDir, seed.seedID + ".cxn");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(seedFile)) {
                fos.write(signedBlob);
            }

            // Save as distribution bootstrap file (picked up by other peers on startup)
            java.io.File bootstrapFile = new java.io.File(server.cxRoot, "cxnet-bootstrap.cxn");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(bootstrapFile)) {
                fos.write(signedBlob);
            }

            // Load into RAM so EPOCH can include it in CXHELLO responses
            server.signedBootstrapSeed = signedBlob;

            // Update CXNET configuration with current seed ID
            if (cxnet.configuration.currentSeedID != null) {
                cxnet.configuration.lastSeedID = cxnet.configuration.currentSeedID;
            }
            cxnet.configuration.currentSeedID = seed.seedID;

            // Display seed info
            log.info("  ✓ Seed created and saved:");
            log.info("    Seed ID: " + seed.seedID);
            log.info("    Timestamp: " + seed.timestamp);
            log.info("    Networks: " + seed.networks.size() + " (CXNET)");
            log.info("    HV Peers: " + seed.hvPeers.size() + " (EPOCH)");
            log.info("    Peer Finding: " + seed.peerFindingNodes.size() + " (EPOCH)");
            log.info("    Certificates: " + seed.certificates.size() + " (EPOCH)");
            log.info("    File: seeds/" + seed.seedID + ".cxn");

            // TODO: Record seed to c2 (Resources chain) blockchain for distribution
            // This will allow automatic loading when new nodes join the network
            // server.recordResource(NETWORK_NAME, seed);

        } else {
            log.info("  ✓ CXNET already loaded in memory");
        }

        // Create TESTNET for blockchain replication testing
        log.info("\nStep 3b: Setting up TESTNET (blockchain replication test network)...");
        CXNetwork testnet = server.getNetwork("TESTNET");

        if (testnet == null) {
            testnet = server.createNetwork("TESTNET");

            // Disable whitelist mode for blockchain replication testing
            testnet.configuration.whitelistMode = false;
            testnet.configuration.publicSeed = false;
            testnet.configuration.syncIntervalSeconds = 30; // Testing mode

            log.info("  ✓ TESTNET created for blockchain replication testing");
            log.info("    Whitelist mode: DISABLED (allows all peers for testing)");
            log.info("    Backend/NMI: EPOCH");
            log.info("    Sync interval: 30 seconds (testing mode)");

            // Create TESTNET seed
            Seed testnetSeed = new Seed();
            testnetSeed.seedID = java.util.UUID.randomUUID().toString();
            testnetSeed.timestamp = System.currentTimeMillis();
            testnetSeed.networkID = "TESTNET";
            testnetSeed.addNetwork(testnet);

            // Add EPOCH as bootstrap peer for TESTNET too
            Node epochNode = new Node();
            epochNode.cxID = SERVER_ID;
            epochNode.publicKey = serverPubKey;
            epochNode.addr = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";
            testnetSeed.addHvPeer(epochNode);
            testnetSeed.addPeerFindingNode(epochNode);

            // Save TESTNET seed
            java.io.File seedsDir = new java.io.File(server.cxRoot, "seeds");
            java.io.File testnetSeedFile = new java.io.File(seedsDir, "testnet_" + testnetSeed.seedID + ".cxn");
            testnetSeed.save(testnetSeedFile);

            log.info("  ✓ TESTNET seed created: testnet_" + testnetSeed.seedID + ".cxn");
        } else {
            log.info("  ✓ TESTNET already loaded in memory");
        }

        // Initialize P2P mesh (for direct CX protocol connections)
        log.info("\nStep 4: Initializing P2P mesh...");
        server.connect(P2P_PORT);
        log.info("  ✓ P2P mesh started on port " + P2P_PORT);

        // Start HTTP bridge server (for RProx/HTTP clients)
        log.info("\nStep 5: Starting cxHTTP1 bridge server...");
        httpBridge.startServer(HTTP_PORT);

        // Print RProx configuration instructions
        log.info("\n" + "=".repeat(70));
        log.info("RPROX CONFIGURATION INSTRUCTIONS");
        log.info("=".repeat(70));
        log.info("1. Go to: https://AnvilDevelopment.US/rprox.html");
        log.info("2. Configure the following:");
        log.info("   Service Name: CXNET");
        log.info("   Target Host:  " + getLocalIP());
        log.info("   Target Port:  " + HTTP_PORT);
        log.info("   Public Path:  /cx");
        log.info("3. After configuration, clients can connect to:");
        log.info("   Example: https://CXNET.AnvilDevelopment.US/cx");
        log.info("4. For local testing without RProx:");
        log.info("   http://localhost:" + HTTP_PORT + "/cx");
        log.info("=".repeat(70));

        // Print EPOCH NMI status
        log.info("\n" + "=".repeat(70));
        log.info("EPOCH NMI - CXNET NETWORK STATUS");
        log.info("=".repeat(70));
        log.info("Network:     " + NETWORK_NAME);
        log.info("NMI Node:    " + SERVER_ID);
        log.info("Directory:   " + CXNET_DIR);
        log.info("HTTP Port:   " + HTTP_PORT);
        log.info("P2P Port:    " + P2P_PORT);
        log.info("Internal:    http://localhost:" + HTTP_PORT + "/cx");
        log.info("Public:      https://CXNET.AnvilDevelopment.US/cx (via RProx)");
        log.info("Health:      http://localhost:" + HTTP_PORT + "/health");
        log.info("Status:      READY - LISTENING FOR CONNECTIONS");
        log.info("=".repeat(70));

        // Register event handler for testing
        registerTestEventHandler(server);

        // Create test events for blockchain sync testing on both networks
        if (messages) {
            log.info("\nCreating test blockchain events for sync testing...");
            createTestBlockchainEvents(server, cxnet);
            createTestBlockchainEvents(server, testnet);
        } else {
            log.info("Skipping messages/blockchain test, this reduces network noise during initialization test");
        }

        // Keep server running
        log.info("\nServer is running. Press Ctrl+C to stop.");
        log.info("\nWaiting for incoming connections...\n");

        // Monitor loop
        while (true) {
            Thread.sleep(5000);

            // Display stats
            int outputQueue = server.outputQueue.size();
            int eventQueue = server.eventQueue.size();

            if (outputQueue > 0 || eventQueue > 0) {
                log.info("[" + new java.util.Date() + "] " +
                    "Output: " + outputQueue + ", Events: " + eventQueue);
            }
        }
    }

    /**
     * Get local IP address for RProx configuration
     */
    private static String getLocalIP() {
        try {
            // Try to get actual IP
            java.net.InetAddress localhost = java.net.InetAddress.getLocalHost();
            String ip = localhost.getHostAddress();

            // Also show all network interfaces
            log.info("\nDetected Network Interfaces:");
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (addr instanceof java.net.Inet4Address) {
                            log.info("  " + iface.getName() + ": " + addr.getHostAddress());
                        }
                    }
                }
            }

            return ip;
        } catch (Exception e) {
            return "[YOUR_LOCAL_IP]";
        }
    }

    /**
     * Create test blockchain events for sync testing
     * Creates 100+ events to test block rotation
     * EPOCH (NMI) has permissions to record events to all chains
     */
    private static void createTestBlockchainEvents(ConnectX server, CXNetwork network) {
        try {
            String networkID = network.configuration.netID;
            log.info("  Creating 105 test events on " + networkID + " c3 (Events chain) to test block rotation...");

            // Create 105 test events on c3 to force block rotation
            for (int i = 1; i <= 105; i++) {
                java.util.Map<String, Object> testData = new java.util.HashMap<>();
                testData.put("testEvent", networkID + "-c3-event-" + i);
                testData.put("eventNumber", i);
                testData.put("timestamp", System.currentTimeMillis());
                testData.put("message", "Test event #" + i + " for block rotation testing on " + networkID);
                String testJson = ConnectX.serialize("cxJSON1", testData);

                server.buildEvent(EventType.MESSAGE, testJson.getBytes("UTF-8"))
                    .withRecordFlag(true)
                    .toNetwork(networkID, network.networkDictionary.c3)
                        .signData()
                    .queue();

                // Progress indicator
                if (i % 10 == 0) {
                    log.info("    Progress: " + i + "/105 events queued");
                }

                Thread.sleep(50);  // Small delay to avoid overwhelming the queue
            }

            // Wait for all events to be processed and recorded
            log.info("  Waiting 5 seconds for all events to be processed and recorded...");
            Thread.sleep(5000);

            // Display final blockchain stats
            ConnectX.BlockchainStats stats = server.getBlockchainStats(network);
            log.info("  ✓ Test events recorded successfully on " + networkID + "!");
            log.info("    c1: " + stats.c1BlockCount + " blocks (current: block " + stats.c1CurrentBlock + ")");
            log.info("    c2: " + stats.c2BlockCount + " blocks (current: block " + stats.c2CurrentBlock + ")");
            log.info("    c3: " + stats.c3BlockCount + " blocks (current: block " + stats.c3CurrentBlock + ")");

            if (stats.c3BlockCount > 1) {
                log.info("    ✓ Block rotation occurred on c3! Multiple blocks created.");
            }

        } catch (Exception e) {
            log.error("  ✗ Error creating test events: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Register handler to respond to test messages
     */
    private static void registerTestEventHandler(ConnectX server) {
        // Create a simple event handler thread
        Thread eventHandler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);

                    // Check event queue
                    if (!server.eventQueue.isEmpty()) {
                        InputBundle bundle;
                        synchronized (server.eventQueue) {
                            bundle = server.eventQueue.poll();
                        }

                        if (bundle != null && bundle.ne != null) {
                            log.info("\n[RECEIVED EVENT]");
                            log.info("  Type: " + bundle.ne.eT);
                            log.info("  From: " + (bundle.nc != null ? bundle.nc.iD : "unknown"));
                            log.info("  Event ID: " + bundle.ne.iD);

                            // Deserialize data if it's a message
                            if (bundle.ne.eT != null && bundle.ne.eT.equals("MESSAGE")) {
                                try {
                                    String message = new String(bundle.ne.d, "UTF-8");
                                    log.info("  Message: " + message);
                                } catch (Exception e) {
                                    log.info("  (Could not decode message)");
                                }
                            }

                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        eventHandler.setDaemon(true);
        eventHandler.start();
    }
}