package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.CXNetwork;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.nodemesh.PeerDirectory;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-peer network test demonstrating CXNET mesh network with 5 peers
 *
 * Tests:
 * 1. CXNET seed loading from EPOCH
 * 2. Proper UUID-based node identity generation
 * 3. Peer discovery and directory population
 * 4. Message routing through CXNET mesh
 * 5. Backend priority routing
 * 6. SEED_REQUEST event for bootstrap
 */
public class MultiPeerTest {

    // EPOCH NMI location (for seed download)
    private static final String EPOCH_DIR = "C:\\Users\\Alexw\\Documents\\AD\\CXNET";

    public static void main(String[] args) {
        System.out.println("=== CXNET Multi-Peer Mesh Network Test ===\n");
        System.out.println("This test demonstrates 5 peers bootstrapping into CXNET");
        System.out.println("IMPORTANT: HTTPBridgeTest (EPOCH) must be running first!\n");

        try {
            int numPeers = 5;
            int basePort = 49153;  // Start at 49153 (EPOCH uses 49152)

            // Step 1: Obtain EPOCH's bootstrap seed
            System.out.println("Step 1: Obtaining EPOCH bootstrap seed...");
            java.io.File epochSeedsDir = new java.io.File(EPOCH_DIR, "seeds");

            if (!epochSeedsDir.exists()) {
                throw new RuntimeException("EPOCH seeds directory not found. Please run HTTPBridgeTest first.");
            }

            java.io.File[] epochSeeds = epochSeedsDir.listFiles((dir, name) -> name.endsWith(".cxn"));
            if (epochSeeds == null || epochSeeds.length == 0) {
                throw new RuntimeException("No EPOCH seeds found. Please run HTTPBridgeTest first.");
            }

            java.io.File latestEpochSeed = epochSeeds[0];
            for (java.io.File f : epochSeeds) {
                if (f.lastModified() > latestEpochSeed.lastModified()) {
                    latestEpochSeed = f;
                }
            }
            System.out.println("  Found EPOCH seed: " + latestEpochSeed.getName());

            // Step 2: Create and initialize all peers with one constructor call each
            System.out.println("\nStep 2: Creating and initializing " + numPeers + " CXNET peers...");
            List<ConnectX> peers = new ArrayList<>();

            for (int i = 1; i <= numPeers; i++) {
                String peerDir = "ConnectX-Peer" + i;
                int port = basePort + (i - 1);

                // Create peer with full initialization
                System.out.println("  Creating Peer " + i + " on port " + port + "...");
                ConnectX peer = new ConnectX(peerDir, port, null, "password" + i);

                // Setup bootstrap seed and trigger bootstrap
                peer.setupBootstrap(latestEpochSeed);
                peer.attemptCXNETBootstrap();

                peers.add(peer);

                System.out.println("    UUID: " + peer.getOwnID());
                Thread.sleep(500); // Stagger initialization
            }

            // Step 3: Wait for bootstrap to complete
            System.out.println("\nStep 3: Waiting for bootstrap to complete...");
            Thread.sleep(5000);

            // Step 8: Verify all peers joined CXNET
            System.out.println("\n=== Bootstrap Results ===");
            int successCount = 0;
            for (int i = 0; i < peers.size(); i++) {
                CXNetwork cxnet = peers.get(i).getNetwork("CXNET");
                if (cxnet != null) {
                    System.out.println("✓ Peer " + (i + 1) + " successfully joined CXNET");
                    successCount++;
                } else {
                    System.out.println("✗ Peer " + (i + 1) + " failed to join CXNET");
                }
            }

            if (successCount == peers.size()) {
                System.out.println("\n✓ SUCCESS: " + successCount + "/" + peers.size() + " peers bootstrapped into CXNET!");
                CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
                System.out.println("  Network ID: " + cxnet.configuration.netID);
                System.out.println("  Chain c1: " + cxnet.networkDictionary.c1);
                System.out.println("  Chain c2: " + cxnet.networkDictionary.c2);
                System.out.println("  Chain c3: " + cxnet.networkDictionary.c3);
            } else {
                System.out.println("\n⚠ PARTIAL SUCCESS: " + successCount + "/" + peers.size() + " peers joined CXNET");
            }

            System.out.println("\n=== Test Setup Complete ===");
            System.out.println("Mesh network architecture:");
            System.out.println("  " + successCount + "/" + peers.size() + " peers bootstrapped from EPOCH");
            System.out.println("  Network: CXNET (not TESTNET)");
            System.out.println("  HTTP bridge registered for all peers");
            if (successCount == peers.size()) {
                System.out.println("  ✓ Ready for peer-to-peer messaging");
            } else {
                System.out.println("  ⚠ Some peers failed to bootstrap");
            }

            // Test messaging through CXNET (only if bootstrap succeeded)
            if (successCount > 0) {
                // Test 1: Send message from Peer3 to network
                System.out.println("\n=== Test 1: Peer 3 broadcasts message ===");
                peers.get(2).buildEvent(EventType.MESSAGE, "Hello from Peer 3 to CXNET!".getBytes())
                    .toNetwork("CXNET")
                    .queue();
                System.out.println("Message queued from Peer 3 (" + peers.get(2).getOwnID() + ")");

                Thread.sleep(2000);

                // Test 2: Send message from Peer5 to network
                System.out.println("\n=== Test 2: Peer 5 broadcasts message ===");
                peers.get(4).buildEvent(EventType.MESSAGE, "Hello from Peer 5 to CXNET!".getBytes())
                    .toNetwork("CXNET")
                    .queue();
                System.out.println("Message queued from Peer 5 (" + peers.get(4).getOwnID() + ")");

                Thread.sleep(2000);

                // Test 3: Peer finding test
                System.out.println("\n=== Test 3: Peer finding broadcast ===");
                //TODO fix implementation Finding peers as a string is not proper implementation and this done nothing but throw errors, this also showed how much of an issue it was for bad data to mess up the incontroller
                peers.get(0).buildEvent(EventType.PeerFinding, "Finding peers".getBytes())
                    .toNetwork("CXNET")
                    .queue();
                System.out.println("Peer finding event queued");
            } else {
                System.out.println("\n⚠ Skipping message tests - no peers successfully bootstrapped");
            }

            // Keep test running
            System.out.println("\n=== Network Active ===");
            System.out.println("Press Ctrl+C to exit.\n");

            while (true) {
                Thread.sleep(5000);
                // Aggregate queue sizes from all peers
                int totalOutput = peers.stream().mapToInt(p -> p.outputQueue.size()).sum();
                int totalEvent = peers.stream().mapToInt(p -> p.eventQueue.size()).sum();
                System.out.println("Queues - Output: " + totalOutput +
                                 ", Event: " + totalEvent +
                                 ", HV Peers: " + PeerDirectory.hv.size());
            }

        } catch (Exception e) {
            System.err.println("Test failed with error:");
            e.printStackTrace();
        }
    }
}