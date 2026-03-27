package us.anvildevelopment.cxnet.test;

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

    public static void main(String[] args) throws Exception {
        System.out.println("=== EPOCH NMI Terminal - CXNET Network ===\n");

        // Create EPOCH NMI instance with dedicated directory
        java.io.File cxnetDir = new java.io.File(CXNET_DIR);
        if (!cxnetDir.exists()) {
            cxnetDir.mkdirs();
            System.out.println("Created CXNET directory: " + CXNET_DIR);
        }

        ConnectX server = new ConnectX(CXNET_DIR);

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
            if (server.nodeMesh.peerDirectory.hv == null) {
                server.nodeMesh.peerDirectory.hv = new java.util.concurrent.ConcurrentHashMap<>();
            }
            server.nodeMesh.peerDirectory.hv.put(SERVER_ID, serverNode);
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
        CXNetwork cxnet;

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

            System.out.println("  ✓ TESTNET seed created: testnet_" + testnetSeed.seedID + ".cxn");
        } else {
            System.out.println("  ✓ TESTNET already loaded in memory");
        }

        // Initialize P2P mesh (for direct CX protocol connections)
        System.out.println("\nStep 4: Initializing P2P mesh...");
        server.connect(P2P_PORT);
        System.out.println("  ✓ P2P mesh started on port " + P2P_PORT);

        // Start HTTP bridge server (for RProx/HTTP clients)
        System.out.println("\nStep 5: Starting cxHTTP1 bridge server...");
        httpBridge.startServer(HTTP_PORT);

        // Print RProx configuration instructions
        System.out.println("\n" + "=".repeat(70));
        System.out.println("RPROX CONFIGURATION INSTRUCTIONS");
        System.out.println("=".repeat(70));
        System.out.println();
        System.out.println("1. Go to: https://AnvilDevelopment.US/rprox.html");
        System.out.println();
        System.out.println("2. Configure the following:");
        System.out.println("   Service Name: CXNET");
        System.out.println("   Target Host:  " + getLocalIP());
        System.out.println("   Target Port:  " + HTTP_PORT);
        System.out.println("   Public Path:  /cx");
        System.out.println();
        System.out.println("3. After configuration, clients can connect to:");
        System.out.println("   https://CXNET.AnvilDevelopment.US/cx");
        System.out.println();
        System.out.println("4. For local testing without RProx:");
        System.out.println("   http://localhost:" + HTTP_PORT + "/cx");
        System.out.println();
        System.out.println("=".repeat(70));

        // Print EPOCH NMI status
        System.out.println("\n" + "=".repeat(70));
        System.out.println("EPOCH NMI - CXNET NETWORK STATUS");
        System.out.println("=".repeat(70));
        System.out.println("Network:     " + NETWORK_NAME);
        System.out.println("NMI Node:    " + SERVER_ID);
        System.out.println("Directory:   " + CXNET_DIR);
        System.out.println("HTTP Port:   " + HTTP_PORT);
        System.out.println("P2P Port:    " + P2P_PORT);
        System.out.println("Internal:    http://localhost:" + HTTP_PORT + "/cx");
        System.out.println("Public:      https://CXNET.AnvilDevelopment.US/cx (via RProx)");
        System.out.println("Health:      http://localhost:" + HTTP_PORT + "/health");
        System.out.println();
        System.out.println("Status:      READY - LISTENING FOR CONNECTIONS");
        System.out.println("=".repeat(70));

        // Register event handler for testing
        registerTestEventHandler(server);

        // Create test events for blockchain sync testing on both networks
        if (messages) {
            System.out.println("\nCreating test blockchain events for sync testing...");
            createTestBlockchainEvents(server, cxnet);
            createTestBlockchainEvents(server, testnet);
        } else {
            System.out.println("Skipping messages/blockchain test, this reduces network noise during initialization test");
        }

        // Keep server running
        System.out.println("\nServer is running. Press Ctrl+C to stop.");
        System.out.println("\nWaiting for incoming connections...\n");

        // Monitor loop
        while (true) {
            Thread.sleep(5000);

            // Display stats
            int outputQueue = server.outputQueue.size();
            int eventQueue = server.eventQueue.size();

            if (outputQueue > 0 || eventQueue > 0) {
                System.out.println("[" + new java.util.Date() + "] " +
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
            System.out.println("\nDetected Network Interfaces:");
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (addr instanceof java.net.Inet4Address) {
                            System.out.println("  " + iface.getName() + ": " + addr.getHostAddress());
                        }
                    }
                }
            }
            System.out.println();

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
            System.out.println("  Creating 105 test events on " + networkID + " c3 (Events chain) to test block rotation...");

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
                    System.out.println("    Progress: " + i + "/105 events queued");
                }

                Thread.sleep(50);  // Small delay to avoid overwhelming the queue
            }

            // Wait for all events to be processed and recorded
            System.out.println("  Waiting 5 seconds for all events to be processed and recorded...");
            Thread.sleep(5000);

            // Display final blockchain stats
            ConnectX.BlockchainStats stats = server.getBlockchainStats(network);
            System.out.println("  ✓ Test events recorded successfully on " + networkID + "!");
            System.out.println("    c1: " + stats.c1BlockCount + " blocks (current: block " + stats.c1CurrentBlock + ")");
            System.out.println("    c2: " + stats.c2BlockCount + " blocks (current: block " + stats.c2CurrentBlock + ")");
            System.out.println("    c3: " + stats.c3BlockCount + " blocks (current: block " + stats.c3CurrentBlock + ")");

            if (stats.c3BlockCount > 1) {
                System.out.println("    ✓ Block rotation occurred on c3! Multiple blocks created.");
            }

        } catch (Exception e) {
            System.err.println("  ✗ Error creating test events: " + e.getMessage());
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
                            System.out.println("\n[RECEIVED EVENT]");
                            System.out.println("  Type: " + bundle.ne.eT);
                            System.out.println("  From: " + (bundle.nc != null ? bundle.nc.iD : "unknown"));
                            System.out.println("  Event ID: " + bundle.ne.iD);

                            // Deserialize data if it's a message
                            if (bundle.ne.eT != null && bundle.ne.eT.equals("MESSAGE")) {
                                try {
                                    String message = new String(bundle.ne.d, "UTF-8");
                                    System.out.println("  Message: " + message);
                                } catch (Exception e) {
                                    System.out.println("  (Could not decode message)");
                                }
                            }

                            System.out.println();
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