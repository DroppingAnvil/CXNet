package dev.droppinganvil.v3.test;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.events.NetworkEvent;
import dev.droppinganvil.v3.network.nodemesh.Node;
import dev.droppinganvil.v3.network.nodemesh.OutputBundle;
import dev.droppinganvil.v3.network.nodemesh.PeerDirectory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple test to demonstrate 2-peer network communication
 *
 * Tests:
 * 1. Network initialization on two peers
 * 2. Peer discovery
 * 3. Basic event transmission
 */
public class TwoPeerTest {

    public static void main(String[] args) {
        System.out.println("=== ConnectX Two-Peer Network Test ===\n");

        try {
            // Initialize peer directory maps
            PeerDirectory.peerCache = new ConcurrentHashMap<>();
            PeerDirectory.seen = new ConcurrentHashMap<>();
            PeerDirectory.lan = new ConcurrentHashMap<>();
            PeerDirectory.hv = new ConcurrentHashMap<>();

            // Create Peer 1 with its own directory
            System.out.println("Creating Peer 1...");
            ConnectX peer1 = new ConnectX("ConnectX-Peer1");

            // Create Peer 2 with its own directory
            System.out.println("Creating Peer 2...");
            ConnectX peer2 = new ConnectX("ConnectX-Peer2");

            // Initialize networks on different ports
            System.out.println("\nInitializing Peer 1 network on port 49152...");
            peer1.connect(49152);

            System.out.println("Initializing Peer 2 network on port 49153...");
            peer2.connect(49153);

            System.out.println("\n=== Network Initialization Complete ===");
            System.out.println("Peer 1 listening on: 127.0.0.1:49152");
            System.out.println("Peer 2 listening on: 127.0.0.1:49153");

            // Give threads time to start
            Thread.sleep(1000);

            // Initialize PGP keys for both peers FIRST (before creating nodes)
            System.out.println("\n=== Initializing Cryptography ===");

            try {
                // For testing, create a simple cx.asc if it doesn't exist
                // In production, this would be the ConnectX network master key
                java.io.File peer1CxAsc = new java.io.File(peer1.cxRoot, "cx.asc");
                java.io.File peer2CxAsc = new java.io.File(peer2.cxRoot, "cx.asc");

                if (!peer1CxAsc.exists() || !peer2CxAsc.exists()) {
                    // Generate a temporary network master key for testing
                    // In production, this would be provided by the network
                    System.out.println("Generating test network master key...");
                    org.bouncycastle.openpgp.PGPSecretKeyRing masterKey = org.pgpainless.PGPainless.generateKeyRing()
                            .modernKeyRing("CX-TestNetwork", "test-network-password");
                    org.bouncycastle.openpgp.PGPPublicKeyRing masterPublic =
                            org.pgpainless.key.util.KeyRingUtils.publicKeyRingFrom(masterKey);

                    // Save to both peers
                    if (!peer1CxAsc.exists()) {
                        java.io.FileOutputStream fos1 = new java.io.FileOutputStream(peer1CxAsc);
                        masterPublic.encode(fos1);
                        fos1.close();
                    }
                    if (!peer2CxAsc.exists()) {
                        java.io.FileOutputStream fos2 = new java.io.FileOutputStream(peer2CxAsc);
                        masterPublic.encode(fos2);
                        fos2.close();
                    }
                    System.out.println("Network master key created");
                }

                // Setup crypto for peer 1
                peer1.encryptionProvider.setup("TESTPEER1", "password1", peer1.cxRoot);
                System.out.println("Peer 1 crypto initialized");

                // Setup crypto for peer 2
                peer2.encryptionProvider.setup("TESTPEER2", "password2", peer2.cxRoot);
                System.out.println("Peer 2 crypto initialized");

            } catch (Exception e) {
                System.err.println("Crypto initialization failed: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }

            // Create test nodes with REAL public keys from encryption providers
            System.out.println("\n=== Creating Node Identities ===");
            Node peer1Node = new Node();
            peer1Node.cxID = "TESTPEER1";
            peer1Node.publicKey = peer1.encryptionProvider.getPublicKey(); // REAL PGP public key
            peer1Node.addr = "127.0.0.1:49152";
            System.out.println("Peer 1 node created with public key: " +
                (peer1Node.publicKey != null ? peer1Node.publicKey.substring(0, Math.min(32, peer1Node.publicKey.length())) + "..." : "null"));

            Node peer2Node = new Node();
            peer2Node.cxID = "TESTPEER2";
            peer2Node.publicKey = peer2.encryptionProvider.getPublicKey(); // REAL PGP public key
            peer2Node.addr = "127.0.0.1:49153";
            System.out.println("Peer 2 node created with public key: " +
                (peer2Node.publicKey != null ? peer2Node.publicKey.substring(0, Math.min(32, peer2Node.publicKey.length())) + "..." : "null"));

            // Set self node for each peer
            System.out.println("\n=== Configuring Peer Identities ===");
            peer1.setSelf(peer1Node);
            peer2.setSelf(peer2Node);
            System.out.println("Peer identities configured");

            // Create blockchain network on Peer1 (as NMI)
            System.out.println("\n=== Creating Blockchain Network ===");
            dev.droppinganvil.v3.network.CXNetwork testNetwork = peer1.createNetwork("TESTNET");
            System.out.println("Network 'TESTNET' created on Peer 1 (NMI)");
            System.out.println("  Chain c1 (Admin): " + testNetwork.networkDictionary.c1);
            System.out.println("  Chain c2 (Resources): " + testNetwork.networkDictionary.c2);
            System.out.println("  Chain c3 (Events): " + testNetwork.networkDictionary.c3);
            System.out.println("  NMI: TESTPEER1");
            System.out.println("  Default permissions configured for joining nodes");

            // Add peer1 to peer directory so peer2 can verify the network signature
            System.out.println("\n=== Registering Peer 1 in Directory ==");
            PeerDirectory.hv.put(peer1Node.cxID, peer1Node);
            System.out.println("Peer 1 node added to directory for signature verification");

            // Export network from Peer1
            System.out.println("\n=== Exporting Network ===");
            java.io.File networkExportFile = new java.io.File(peer1.cxRoot, "testnet.cxn");
            peer1.exportNetwork("TESTNET", networkExportFile);
            System.out.println("Network exported to: " + networkExportFile.getAbsolutePath());

            // Import network on Peer2 (will verify signature using Peer1's public key)
            System.out.println("\n=== Importing Network on Peer 2 ===");
            peer2.importNetwork(networkExportFile);
            System.out.println("Network imported successfully");

            // Join network on Peer2
            peer2.joinNetwork("TESTNET");
            System.out.println("Peer 2 joined network TESTNET");

            // Add peer2 to peer directory so peer1 can find it
            System.out.println("\n=== Registering Peers in Directory ===");
            PeerDirectory.hv.put(peer2Node.cxID, peer2Node);
            System.out.println("Peer 2 added to peer directory");

            // Create a test event using CXN scope (network backend routing)
            System.out.println("\n=== Creating Test Event ===");
            NetworkEvent testEvent = new NetworkEvent(EventType.MESSAGE, "Hello from Peer 1 via TESTNET blockchain!".getBytes());
            testEvent.eT = EventType.MESSAGE.name();

            // Create CXPath for routing using CXN scope (network-based routing)
            dev.droppinganvil.v3.network.CXPath path = new dev.droppinganvil.v3.network.CXPath();
            path.scope = "CXN";  // Network scope, not direct peer-to-peer
            path.network = "TESTNET";  // Route through TESTNET network
            testEvent.p = path;

            NetworkContainer nc = new NetworkContainer();
            nc.se = "cxJSON1";
            nc.s = false; // Not E2E encrypted for test

            OutputBundle bundle = new OutputBundle(testEvent, null, null, null, nc);

            // Queue event for transmission
            System.out.println("Queueing event for transmission...");
            System.out.println("  Event Type: " + testEvent.eT);
            System.out.println("  Target Scope: " + testEvent.p.scope);
            System.out.println("  Target Network: " + testEvent.p.network);
            System.out.println("  Data: " + new String(testEvent.d));

            ConnectX.outputQueue.add(bundle);

            System.out.println("\n=== Test Setup Complete ===");
            System.out.println("Blockchain-based network architecture:");
            System.out.println("  ✓ Real PGP keys initialized");
            System.out.println("  ✓ Nodes created with actual public keys");
            System.out.println("  ✓ Network TESTNET created with 3 chains");
            System.out.println("  ✓ Network exported and imported");
            System.out.println("  ✓ Peer 2 joined with default permissions");
            System.out.println("  ✓ CXN scope routing configured");
            System.out.println("\nNetwork threads are running. Press Ctrl+C to exit.");

            // Keep test running
            while (true) {
                Thread.sleep(5000);
                System.out.println("\nQueues status:");
                System.out.println("  Output queue size: " + ConnectX.outputQueue.size());
                System.out.println("  Event queue size: " + ConnectX.eventQueue.size());
            }

        } catch (Exception e) {
            System.err.println("Test failed with error:");
            e.printStackTrace();
        }
    }
}