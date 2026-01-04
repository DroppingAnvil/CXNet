package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.nodemesh.Node;
import dev.droppinganvil.v3.network.nodemesh.PeerDirectory;
import dev.droppinganvil.v3.network.nodemesh.bridge.http.HTTPBridgeProvider;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Test automatic CXNET bootstrap from EPOCH
 *
 * This test creates a new peer that has never joined CXNET before
 * and verifies it can automatically:
 * 1. Contact EPOCH via HTTP bridge
 * 2. Send NewNode introduction
 * 3. Request CXNET seed
 * 4. Receive and apply seed
 * 5. Join CXNET network
 */
public class BootstrapTest {

    public static void main(String[] args) {
        System.out.println("=== CXNET Automatic Bootstrap Test ===\n");

        try {
            // Initialize peer directory

            // This shouldnt be needed anymore

            //PeerDirectory.peerCache = new ConcurrentHashMap<>();
           // PeerDirectory.seen = new ConcurrentHashMap<>();
            //PeerDirectory.lan = new ConcurrentHashMap<>();
           // PeerDirectory.hv = new ConcurrentHashMap<>();

            // Step 1: Create a new peer
            System.out.println("Step 1: Creating new peer...");
            String peerDir = "ConnectX-BootstrapTest";
            ConnectX peer = new ConnectX(peerDir);
            System.out.println("  ✓ Peer created at: " + peer.cxRoot.getAbsolutePath());

            // Step 2: HTTP bridge is auto-registered by ConnectX constructor
            System.out.println("\nStep 2: HTTP bridge auto-registered");
            System.out.println("  ✓ cxHTTP1 bridge available (per-instance)");

            // Step 3: Copy EPOCH's seed as bootstrap seed
            System.out.println("\nStep 3: Obtaining bootstrap seed...");
            String epochDir = "C:\\Users\\Alexw\\Documents\\AD\\CXNET";
            java.io.File epochSeedsDir = new java.io.File(epochDir, "seeds");

            if (!epochSeedsDir.exists()) {
                throw new RuntimeException("EPOCH seeds directory not found. Please run HTTPBridgeTest first.");
            }

            // Find the most recent seed from EPOCH
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

            // Copy to our directory as bootstrap seed
            java.io.File bootstrapSeed = new java.io.File(peer.cxRoot, "cxnet-bootstrap.cxn");
            java.nio.file.Files.copy(
                latestEpochSeed.toPath(),
                bootstrapSeed.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
            System.out.println("  ✓ Copied bootstrap seed from EPOCH");
            System.out.println("  Source: " + latestEpochSeed.getName());

            // Load and apply bootstrap seed to extract cx.asc
            System.out.println("\nApplying bootstrap seed...");
            dev.droppinganvil.v3.network.Seed seed = dev.droppinganvil.v3.network.Seed.load(bootstrapSeed);
            System.out.println("  Seed ID: " + seed.seedID);
            System.out.println("  Networks: " + seed.networks.size());
            System.out.println("  Certificates: " + seed.certificates.size());

            // Apply seed (this will extract cx.asc from EPOCH's public key)
            // Note: We need to manually apply before connect() since we haven't initialized crypto yet
            java.io.File cxAsc = new java.io.File(peer.cxRoot, "cx.asc");
            if (seed.certificates != null && seed.certificates.containsKey("00000000-0000-0000-0000-000000000001")) {
                String nmiPublicKey = seed.certificates.get("00000000-0000-0000-0000-000000000001");
                java.io.FileWriter writer = new java.io.FileWriter(cxAsc);
                writer.write(nmiPublicKey);
                writer.flush();
                writer.close();
                System.out.println("  ✓ Extracted EPOCH's public key to cx.asc");
            }

            // Step 4: Initialize crypto (now we have cx.asc!)
            System.out.println("\nStep 4: Initializing cryptography...");
            String peerUUID = java.util.UUID.randomUUID().toString();
            peer.encryptionProvider.setup(peerUUID, "bootstrap-test-password", peer.cxRoot);
            System.out.println("  ✓ Crypto initialized");
            System.out.println("  Peer UUID: " + peerUUID);

            // Step 5: Create node identity
            System.out.println("\nStep 5: Creating node identity...");
            Node selfNode = new Node();
            selfNode.cxID = peerUUID;
            selfNode.publicKey = peer.encryptionProvider.getPublicKey();
            selfNode.addr = "127.0.0.1:49154"; // Different port from EPOCH (49152)
            peer.setSelf(selfNode);
            System.out.println("  ✓ Node identity created");

            // Step 6: Connect (this triggers automatic bootstrap!)
            System.out.println("\nStep 6: Connecting to CXNET (automatic bootstrap)...");
            System.out.println("  → This will automatically:");
            System.out.println("    1. Check for local CXNET seed");
            System.out.println("    2. If not found, contact EPOCH via HTTP bridge");
            System.out.println("    3. Send NewNode introduction");
            System.out.println("    4. Request CXNET seed");
            System.out.println("    5. Receive and apply seed");
            System.out.println();

            peer.connect(49154); // Triggers attemptCXNETBootstrap()

            // Wait for bootstrap to complete
            System.out.println("\nWaiting for bootstrap to complete...");
            Thread.sleep(5000);

            // Step 7: Check if CXNET was loaded
            System.out.println("\n=== Bootstrap Results ===");
            dev.droppinganvil.v3.network.CXNetwork cxnet = peer.getNetwork("CXNET");

            if (cxnet != null) {
                System.out.println("✓ SUCCESS: CXNET loaded!");
                System.out.println("  Network ID: " + cxnet.configuration.netID);
                System.out.println("  NMI: " + cxnet.configuration.nmiPub.substring(0, 50) + "...");
                System.out.println("  Backend nodes: " + cxnet.configuration.backendSet);
                System.out.println("  Chain c1: " + cxnet.networkDictionary.c1);
                System.out.println("  Chain c2: " + cxnet.networkDictionary.c2);
                System.out.println("  Chain c3: " + cxnet.networkDictionary.c3);
            } else {
                System.out.println("✗ FAILED: CXNET not loaded");
                System.out.println("  This may be expected if:");
                System.out.println("  - EPOCH is not running");
                System.out.println("  - HTTP bridge is not accessible");
                System.out.println("  - SEED_REQUEST/RESPONSE not implemented");
            }

            // Check peer directory
            //System.out.println("\nPeer Directory:");
            //System.out.println("  HV peers: " + PeerDirectory.hv.size());
            //System.out.println("  Seen peers: " + PeerDirectory.seen.size());
           // for (String cxID : PeerDirectory.hv.keySet()) {
           //     System.out.println("    - " + cxID);
           // }

            // Check for seed files
            System.out.println("\nLocal Seeds:");
            java.io.File seedsDir = new java.io.File(peer.cxRoot, "seeds");
            if (seedsDir.exists()) {
                java.io.File[] seedFiles = seedsDir.listFiles((dir, name) -> name.endsWith(".cxn"));
                if (seedFiles != null && seedFiles.length > 0) {
                    System.out.println("  ✓ " + seedFiles.length + " seed(s) found:");
                    for (java.io.File f : seedFiles) {
                        System.out.println("    - " + f.getName());
                    }
                } else {
                    System.out.println("  No seed files found");
                }
            } else {
                System.out.println("  seeds/ directory does not exist");
            }

            System.out.println("\n=== Test Complete ===");
            System.out.println("Peer is running. Press Ctrl+C to exit.");

            // Keep running
            while (true) {
                Thread.sleep(5000);
                System.out.println("Queues - Output: " + peer.outputQueue.size() +
                                 ", Event: " + peer.eventQueue.size());
            }

        } catch (Exception e) {
            System.err.println("\nTest failed with error:");
            e.printStackTrace();
        }
    }
}