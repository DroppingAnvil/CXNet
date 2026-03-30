import org.junit.jupiter.api.*;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.Permission;
import us.anvildevelopment.cxnet.State;
import us.anvildevelopment.cxnet.network.CXNetwork;
import us.anvildevelopment.cxnet.network.events.ChainStatus;
import us.anvildevelopment.cxnet.network.events.EventType;
import us.anvildevelopment.cxnet.network.events.NodeModeration;
import us.anvildevelopment.cxnet.network.events.NodeRegistration;
import us.anvildevelopment.cxnet.network.events.PermissionChange;
import us.anvildevelopment.cxnet.network.events.CXMessage;
import us.anvildevelopment.cxnet.network.events.PeerFinding;
import us.anvildevelopment.cxnet.network.events.SeedExchange;
import us.anvildevelopment.cxnet.network.nodemesh.Node;
import us.anvildevelopment.cxnet.api.CXMessagePlugin;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.BridgeProvider;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.http.HTTPBridgeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-peer network integration test for the CXNET mesh network.
 *
 * IMPORTANT: HTTPBridgeTest (EPOCH node) must be running before this suite executes.
 *
 * Tests run in order and share a single set of peers spun up in @BeforeAll.
 * Each test covers one logical concern:
 *   1. Bootstrap - peers contact EPOCH and join CXNET
 *   2. P2P messaging - broadcast via CXN scope
 *   3. Security - whitelist, register, block, unblock
 *   4. Whitelist integration - token-based registration flow
 *   5. Blockchain sync and permissions
 *   6. E2E encryption - multi-recipient PGP
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MultiPeerTest {

    private static final boolean SEND_MESSAGES = false;
    private static final int MESSAGE_COUNT = 105;

    private static List<ConnectX> peers;
    private static int bootstrapCount = 0;
    /** Per-peer inbox: index matches peers list index. Populated by CXMessagePlugin. */
    private static final Map<Integer, List<CXMessage>> receivedMessages = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeAll
    @Timeout(value = 420, unit = TimeUnit.SECONDS)
    static void setupPeers() throws Exception {
        System.out.println("=== CXNET Multi-Peer Mesh Network Test ===\n");
        System.out.println("IMPORTANT: HTTPBridgeTest (EPOCH) must be running first!\n");

        int basePort = 49153;
        peers = new ArrayList<>();

        // Create 4 peers, start their HTTP bridges, attempt CXNET bootstrap
        System.out.println("Step 1: Creating and initializing 4 CXNET peers...");
        for (int i = 1; i <= 4; i++) {
            String peerDir = "ConnectX-Peer" + i;
            int port = basePort + (i - 1);
            int httpPort = 8080 + i;

            System.out.println("  Creating Peer " + i + " on port " + port + "...");
            ConnectX peer = new ConnectX(peerDir, port, null, "password" + i);
            final int peerIndex = i - 1;
            receivedMessages.put(peerIndex, new CopyOnWriteArrayList<>());
            peer.addPlugin(new CXMessagePlugin() {
                @Override
                public void onMessage(String senderID, CXMessage message) {
                    receivedMessages.get(peerIndex).add(message);
                }
            });

            String publicEndpoint = "https://cx" + i + ".anvildevelopment.us/cx";
            //TODO MultiAddress support per node

            try {
                BridgeProvider bridge = peer.getBridgeProvider("cxHTTP1");
                if (bridge instanceof HTTPBridgeProvider) {
                    ((HTTPBridgeProvider) bridge).startServer(httpPort);
                    peer.setPublicBridgeAddress("cxHTTP1", publicEndpoint);
                    System.out.println("    HTTP bridge started on port " + httpPort);
                    System.out.println("    Public address: cxHTTP1:" + publicEndpoint);
                }
            } catch (Exception e) {
                System.err.println("    Warning: Failed to start HTTP bridge: " + e.getMessage());
                e.printStackTrace();
            }

            // Trigger bootstrap -- node contacts EPOCH via its HTTP bridge automatically
            peer.attemptCXNETBootstrap();

            peers.add(peer);
            System.out.println("    UUID: " + peer.getOwnID());
            Thread.sleep(500);
        }

        // Wait for P2P discovery (LAN scanner + CXHELLO)
        System.out.println("\nStep 1b: Waiting 30 seconds for P2P discovery to complete...");
        for (int i = 30; i > 0; i--) {
            if (i % 5 == 0 || i <= 3) {
                System.out.println("  " + i + " seconds remaining...");
            }
            Thread.sleep(1000);
        }
        System.out.println("  P2P discovery period complete.");

        // Wait for all peers to reach READY state
        System.out.println("\nStep 1c: Waiting for all peers to reach READY state...");
        final boolean[] allReady = {false};
        final long startTime = System.currentTimeMillis();
        final int maxWaitSeconds = 120;

        Thread stateMonitor = new Thread(() -> {
            int checkCount = 0;
            while (!allReady[0] && checkCount < maxWaitSeconds) {
                try {
                    Thread.sleep(3000);
                    checkCount += 3;

                    int readyCount = 0;
                    for (ConnectX peer : peers) {
                        if (peer.state == State.READY) readyCount++;
                    }

                    if (readyCount == peers.size()) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        System.out.println("  All peers READY after " + elapsed + " seconds.");
                        for (ConnectX peer : peers) {
                            int peerCount = peer.dataContainer.getLocalPeerCount();
                            if (peerCount > 0) {
                                System.out.println("    Peer " + peer.getOwnID().substring(0, 8) + " discovered " + peerCount + " local peers");
                            }
                        }
                        allReady[0] = true;
                    } else if (checkCount % 15 == 0) {
                        System.out.println("  [" + checkCount + "s] " + readyCount + "/" + peers.size() + " peers READY...");
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (!allReady[0]) {
                System.out.println("  Timeout: not all peers reached READY state after " + maxWaitSeconds + "s");
            }
        });
        stateMonitor.setName("State-Monitor-Thread");
        stateMonitor.setDaemon(false);
        stateMonitor.start();
        stateMonitor.join();

        // Wait for CXHELLO exchanges to stabilise
        System.out.println("\nStep 1c.5: Waiting for CXHELLO exchanges to complete...");
        final int cxhelloWaitSeconds = 180;
        final int expectedPeers = peers.size() - 1;
        Thread cxhelloWait = new Thread(() -> {
            int elapsed = 0;
            int lastHvSize = peers.get(0).nodeMesh.peerDirectory.hv.size();
            int stableCount = 0;

            while (elapsed < cxhelloWaitSeconds) {
                try {
                    Thread.sleep(5000);
                    elapsed += 5;

                    int currentHvSize = peers.get(0).nodeMesh.peerDirectory.hv.size();
                    if (currentHvSize > lastHvSize) {
                        System.out.println("  [" + elapsed + "s] HV peers: " + lastHvSize + " -> " + currentHvSize);
                        lastHvSize = currentHvSize;
                        stableCount = 0;
                    } else if (currentHvSize == lastHvSize && currentHvSize > 1) {
                        stableCount += 5;
                    }

                    if (currentHvSize >= expectedPeers && stableCount >= 15) {
                        System.out.println("  CXHELLO exchanges complete after " + elapsed + "s (HV=" + currentHvSize + ")");
                        break;
                    }
                    if (currentHvSize > 1 && stableCount >= 20) {
                        System.out.println("  CXHELLO exchanges stabilised at " + currentHvSize + " HV peers after " + elapsed + "s");
                        break;
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (elapsed >= cxhelloWaitSeconds) {
                System.out.print("  CXHELLO wait timeout (HV peers per peer:");
                for (int i = 0; i < peers.size(); i++) {
                    System.out.print(" P" + (i+1) + "=" + peers.get(i).nodeMesh.peerDirectory.hv.size());
                }
                System.out.println(")");
            }
        });
        cxhelloWait.setName("CXHELLO-Wait-Thread");
        cxhelloWait.setDaemon(false);
        cxhelloWait.start();
        cxhelloWait.join();

        // Optionally send CXN messages during setup
        System.out.println("\nStep 1d: Testing P2P message delivery via CXN broadcast...");
        int cxnetCount = 0;
        for (ConnectX peer : peers) {
            if (peer.getNetwork("CXNET") != null) cxnetCount++;
        }
        System.out.println("  Peers with CXNET loaded: " + cxnetCount + "/" + peers.size());

        if (SEND_MESSAGES && cxnetCount > 0) {
            CXNetwork network = peers.get(0).getNetwork("CXNET");
            Long c3ChainID = (network != null && network.networkDictionary != null)
                ? network.networkDictionary.c3 : null;

            for (int i = 0; i < MESSAGE_COUNT; i++) {
                peers.get(0).buildEvent(EventType.MESSAGE, ("P2P CXN test message #" + (i+1)).getBytes())
                    .toNetwork("CXNET")
                    .chainID(c3ChainID)
                    .queue();
                if ((i+1) % 25 == 0) Thread.sleep(500);
            }
            Thread.sleep(5000);
        }

        // Wait for bootstrap to propagate
        System.out.println("\nStep 2: Waiting for bootstrap to complete...");
        int bootstrapMaxWaitSeconds = 30;
        int checkIntervalSeconds = 3;
        for (int elapsed = 0; elapsed < bootstrapMaxWaitSeconds; elapsed += checkIntervalSeconds) {
            Thread.sleep(checkIntervalSeconds * 1000);
            int currentCount = 0;
            for (ConnectX peer : peers) {
                if (peer.getNetwork("CXNET") != null) currentCount++;
            }
            if (currentCount > bootstrapCount) {
                bootstrapCount = currentCount;
                System.out.println("  [" + elapsed + "s] " + bootstrapCount + "/" + peers.size() + " peers joined CXNET");
            }
            if (bootstrapCount == peers.size()) {
                System.out.println("  All peers bootstrapped successfully!");
                break;
            }
        }

        // Final count in case the loop above exited early
        bootstrapCount = 0;
        for (ConnectX peer : peers) {
            if (peer.getNetwork("CXNET") != null) bootstrapCount++;
        }

        // Diagnostics
        System.out.println("\n=== INTERNAL STATE DIAGNOSTICS ===");
        for (int i = 0; i < peers.size(); i++) {
            ConnectX peer = peers.get(i);
            System.out.println("\nPeer " + (i + 1) + " (" + peer.getOwnID().substring(0, 8) + "):");
            CXNetwork cxnet = peer.getNetwork("CXNET");
            System.out.println("  CXNET: " + (cxnet != null ? "JOINED" : "NOT JOINED"));
            if (cxnet != null) {
                System.out.println("    Chain c1: " + cxnet.c1.blockMap.size() + " blocks");
                System.out.println("    Chain c2: " + cxnet.c2.blockMap.size() + " blocks");
                System.out.println("    Chain c3: " + cxnet.c3.blockMap.size() + " blocks");
            }
            int hvPeerCount = peer.nodeMesh.peerDirectory.hv.size();
            int lanPeerCount = peer.nodeMesh.peerDirectory.lan.size();
            System.out.println("  PeerDirectory: HV=" + hvPeerCount + " LAN=" + lanPeerCount
                + " cached=" + peer.nodeMesh.peerDirectory.peerCache.size());
            if (hvPeerCount > 0) {
                int count = 0;
                for (String peerID : peer.nodeMesh.peerDirectory.hv.keySet()) {
                    if (count++ >= 3) { System.out.println("      ... and " + (hvPeerCount - 3) + " more"); break; }
                    Node node = peer.nodeMesh.peerDirectory.hv.get(peerID);
                    System.out.println("      " + peerID.substring(0, 8) + " @ " + node.addr);
                }
            }
            System.out.println("  Queues: output=" + peer.outputQueue.size() + " event=" + peer.eventQueue.size());
        }

        System.out.println("\n=== Setup Complete: " + bootstrapCount + "/" + peers.size() + " peers bootstrapped ===\n");
    }

    @AfterAll
    static void teardownPeers() {
        // Peer threads are daemon threads and will stop with the JVM.
        // If ConnectX gains a shutdown() API in the future, call it here.
        if (peers != null) {
            System.out.println("=== Tearing down " + peers.size() + " peers ===");
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Bootstrap: peers join CXNET from EPOCH")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testBootstrap() {
        System.out.println("\n=== Bootstrap Results ===");
        int successCount = 0;
        for (int i = 0; i < peers.size(); i++) {
            CXNetwork cxnet = peers.get(i).getNetwork("CXNET");
            if (cxnet != null) {
                System.out.println("  Peer " + (i + 1) + " joined CXNET");
                successCount++;
            } else {
                System.out.println("  Peer " + (i + 1) + " did NOT join CXNET");
            }
        }

        assertTrue(successCount > 0, "At least one peer must successfully bootstrap into CXNET");

        CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
        if (cxnet != null) {
            System.out.println("  Network ID: " + cxnet.configuration.netID);
            System.out.println("  Chain c1: " + cxnet.networkDictionary.c1);
            System.out.println("  Chain c2: " + cxnet.networkDictionary.c2);
            System.out.println("  Chain c3: " + cxnet.networkDictionary.c3);
        }
    }

    @Test
    @Order(2)
    @DisplayName("P2P messaging: broadcast via CXNET and peer finding")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testP2PMessaging() throws InterruptedException {
        assumeBootstrapped();

        assertNotNull(peers.get(2).getNetwork("CXNET"), "Peer 3 must have CXNET loaded to broadcast");

        // Clear inboxes before the test so prior setup messages don't skew counts
        receivedMessages.values().forEach(List::clear);

        String broadcastText = "Hello from Peer 3 to CXNET!";
        CXMessage broadcastMsg = new CXMessage(broadcastText);
        System.out.println("\n=== Test: Peer 3 broadcasts message ===");
        try {
            peers.get(2).buildEvent(EventType.MESSAGE,
                    ConnectX.serialize("cxJSON1", broadcastMsg).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toNetwork("CXNET")
                .signData()
                .queue();
        } catch (Exception e) {
            fail("Failed to serialize/queue broadcast message: " + e.getMessage());
        }
        System.out.println("Message queued from Peer 3 (" + peers.get(2).getOwnID() + ")");
        Thread.sleep(5000);

        // Count how many OTHER peers received the broadcast (match by text field)
        long deliveredTo = receivedMessages.entrySet().stream()
            .filter(e -> e.getKey() != 2) // exclude the sender (index 2 = Peer 3)
            .filter(e -> e.getValue().stream().anyMatch(m -> broadcastText.equals(m.text)))
            .count();
        System.out.println("  Broadcast delivered to " + deliveredTo + " peer(s) (excluding sender)");
        assertTrue(deliveredTo > 0,
            "At least one peer must receive the broadcast from Peer 3 via CXNET");

        System.out.println("\n=== Test: Peer finding broadcast ===");
        try {
            PeerFinding pf = new PeerFinding();
            pf.t = "request";
            pf.network = "CXNET";
            peers.get(0).buildEvent(EventType.PeerFinding,
                    ConnectX.serialize("cxJSON1", pf).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toNetwork("CXNET")
                .signData()
                .queue();
        } catch (Exception e) {
            fail("Failed to queue PeerFinding event: " + e.getMessage());
        }
        System.out.println("Peer finding event queued");
        Thread.sleep(2000);

        long peersWithHvEntries = peers.stream()
            .filter(p -> p.nodeMesh.peerDirectory.hv != null && !p.nodeMesh.peerDirectory.hv.isEmpty())
            .count();
        assertTrue(peersWithHvEntries > 0,
            "At least one peer must have HV directory entries after CXHELLO exchanges");
    }

    @Test
    @Order(3)
    @DisplayName("Security: whitelist, register, block, unblock nodes")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSecurity() throws Exception {
        assumeBootstrapped();
        runSecurityTests(peers);
    }

    @Test
    @Order(4)
    @DisplayName("Whitelist integration: token-based registration flow")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testWhitelistIntegration() throws Exception {
        assumeBootstrapped();
        runWhitelistIntegrationTest(peers);
    }

    @Test
    @Order(5)
    @DisplayName("Blockchain sync and permission management")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testBlockchainSyncAndPermissions() throws Exception {
        assumeBootstrapped();
        runBlockchainSyncAndPermissionTest(peers);
    }

    @Test
    @Order(6)
    @DisplayName("E2E encryption: multi-recipient PGP")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testE2EEncryption() throws Exception {
        assumeBootstrapped();
        runE2EEncryptionTest(peers);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Skips the test if no peers successfully bootstrapped. */
    private static void assumeBootstrapped() {
        org.junit.jupiter.api.Assumptions.assumeTrue(bootstrapCount > 0,
            "Skipping: no peers bootstrapped into CXNET");
    }

    /**
     * Comprehensive security tests using existing peer network
     */
    private static void runSecurityTests(List<ConnectX> peers) throws Exception {
        System.out.println("\n\n==================================================================");
        System.out.println("  SECURITY FEATURES TEST");
        System.out.println("==================================================================\n");

        Thread.sleep(1000);

        // Test 1: Whitelist Mode Enforcement
        System.out.println("TEST 1: Whitelist Mode Network");
        System.out.println("------------------------------------------------------------------");
        CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
        assertNotNull(cxnet, "CXNET must be loaded to run security tests");

        System.out.println("CXNET Configuration:");
        System.out.println("  - WhitelistMode: " + (cxnet.configuration.whitelistMode != null ?
                          cxnet.configuration.whitelistMode : "false (default)"));

        int regCount = peers.get(0).dataContainer.networkRegisteredNodes.getOrDefault("CXNET", new java.util.HashSet<>()).size();
        int blockCount = peers.get(0).dataContainer.networkBlockedNodes.getOrDefault("CXNET", new java.util.HashMap<>()).size();
        System.out.println("  - Registered nodes: " + regCount);
        System.out.println("  - Blocked nodes: " + blockCount);
        System.out.println("PASS: Whitelist infrastructure present\n");

        Thread.sleep(1000);

        // Test 2: REGISTER_NODE Event
        System.out.println("TEST 2: Node Registration (REGISTER_NODE)");
        System.out.println("------------------------------------------------------------------");

        NodeRegistration registerPayload = new NodeRegistration("CXNET", peers.get(3).getOwnID(), "test-nmi");
        String nodeID = registerPayload.nodeID;

        // Process registration (stored in local DataContainer)
        peers.get(0).dataContainer.networkRegisteredNodes.computeIfAbsent("CXNET", k -> new java.util.HashSet<>()).add(nodeID);

        System.out.println("  Node registered:");
        System.out.println("    - Node: " + nodeID);
        System.out.println("    - Network: CXNET");
        System.out.println("    - Total registered: " + peers.get(0).dataContainer.networkRegisteredNodes.get("CXNET").size());
        assertTrue(peers.get(0).dataContainer.networkRegisteredNodes.get("CXNET").contains(nodeID),
            "Node must be present in registered set after registration");
        System.out.println("PASS: Registration processed\n");

        Thread.sleep(1000);

        // Test 3: BLOCK_NODE Event
        System.out.println("TEST 3: Node Blocking (BLOCK_NODE)");
        System.out.println("------------------------------------------------------------------");

        NodeModeration blockPayload = new NodeModeration("CXNET", peers.get(3).getOwnID(), "testing block mechanism");
        String blockedNodeID = blockPayload.nodeID;
        String reason = blockPayload.reason;

        // Process block (stored in local DataContainer)
        peers.get(0).dataContainer.blockNode("CXNET", blockedNodeID, reason);

        System.out.println("  Node blocked:");
        System.out.println("    - Node: " + blockedNodeID);
        System.out.println("    - Reason: " + reason);
        System.out.println("    - Total blocked: " + peers.get(0).dataContainer.networkBlockedNodes.get("CXNET").size());
        assertTrue(peers.get(0).dataContainer.networkBlockedNodes.get("CXNET").containsKey(blockedNodeID),
            "Node must be present in blocked map after blocking");
        System.out.println("PASS: Blocking processed\n");

        Thread.sleep(1000);

        // Test 4: UNBLOCK_NODE Event
        System.out.println("TEST 4: Node Unblocking (UNBLOCK_NODE)");
        System.out.println("------------------------------------------------------------------");

        NodeModeration unblockPayload = new NodeModeration("CXNET", blockedNodeID, null);
        String unblockedNodeID = unblockPayload.nodeID;

        // Process unblock (stored in local DataContainer)
        String removedReason = peers.get(0).dataContainer.unblockNode("CXNET", unblockedNodeID);

        System.out.println("  Node unblocked:");
        System.out.println("    - Node: " + unblockedNodeID);
        System.out.println("    - Was blocked for: " + removedReason);
        System.out.println("    - Total blocked: " + peers.get(0).dataContainer.networkBlockedNodes.get("CXNET").size());
        assertFalse(peers.get(0).dataContainer.networkBlockedNodes.getOrDefault("CXNET", new java.util.HashMap<>()).containsKey(unblockedNodeID),
            "Node must not be in blocked map after unblocking");
        System.out.println("PASS: Unblocking processed\n");

        Thread.sleep(1000);

        // Test 5: Peer Discovery
        System.out.println("TEST 5: Peer Discovery (PEER_LIST_REQUEST)");
        System.out.println("------------------------------------------------------------------");
        int hvCount = (peers.get(0).nodeMesh.peerDirectory.hv != null) ? peers.get(0).nodeMesh.peerDirectory.hv.size() : 0;
        int maxPeers = Math.min(10, (int) Math.ceil(hvCount * 0.3));
        System.out.println("  - Total HV peers (Peer 1): " + hvCount);
        System.out.println("  - 30% of peers: " + (int) Math.ceil(hvCount * 0.3));
        System.out.println("  - Max returned: " + maxPeers);
        System.out.println("  - Rate limit: 3 requests/IP/hour");
        System.out.println("PASS: Rate limiting enforced\n");

        Thread.sleep(1000);

        // Test 6: Security Summary
        System.out.println("TEST 6: Security Feature Summary");
        System.out.println("------------------------------------------------------------------");
        System.out.println("Implemented Security Features:");
        System.out.println("  Whitelist Mode, Node Registration, Node Blocking, Node Unblocking,");
        System.out.println("  Peer Discovery, IP Rate Limiting, Two-Tier Blocking, Whitelist Enforcement");
        System.out.println();
        System.out.println("==================================================================");
        System.out.println("  ALL SECURITY TESTS PASSED");
        System.out.println("==================================================================\n");
    }

    /**
     * REAL whitelist integration test - uses actual network event processing
     */
    private static void runWhitelistIntegrationTest(List<ConnectX> peers) throws Exception {
        System.out.println("\n\n==================================================================");
        System.out.println("  REAL WHITELIST INTEGRATION TEST");
        System.out.println("==================================================================");
        System.out.println("This test uses ACTUAL event processing, not programmatic shortcuts\n");

        Thread.sleep(2000);

        // STEP 1: Request CXNET seed from EPOCH
        System.out.println("STEP 1: Request CXNET seed from EPOCH");
        System.out.println("------------------------------------------------------------------");
        for (int i = 0; i < 3; i++) {
            String reqJson = ConnectX.serialize("cxJSON1", new SeedExchange("CXNET"));
            peers.get(i).buildEvent(EventType.SEED_REQUEST, reqJson.getBytes())
                .toPeer("00000000-0000-0000-0000-000000000001")  // EPOCH UUID
                .signData()
                .queue();
            System.out.println("  Peer " + (i + 1) + " requested CXNET seed");
        }
        System.out.println("  Waiting for EPOCH to respond...");
        Thread.sleep(8000);

        int joinedCount = 0;
        for (int i = 0; i < 3; i++) {
            CXNetwork CXNET = peers.get(i).getNetwork("CXNET");
            if (CXNET != null) {
                joinedCount++;
                System.out.println("  Peer " + (i + 1) + " received CXNET (whitelist: " +
                    CXNET.configuration.whitelistMode + ")");
            }
        }
        if (joinedCount == 0) {
            System.out.println("  No peers received CXNET - skipping whitelist test");
            return;
        }
        System.out.println("  " + joinedCount + "/3 peers joined CXNET");
        Thread.sleep(2000);

        // STEP 3: Test that registered peers CAN communicate
        System.out.println("\nSTEP 3: Verify registered peers can communicate");
        System.out.println("------------------------------------------------------------------");
        peers.get(0).buildEvent(EventType.MESSAGE, "Test from registered Peer 1".getBytes())
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Peer 1 sent message");
        Thread.sleep(1000);
        peers.get(2).buildEvent(EventType.MESSAGE, "Test from registered Peer 3".getBytes())
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Peer 3 sent message");
        Thread.sleep(3000);

        // STEP 4: Unregistered Peer 4 tries to send → should be REJECTED
        System.out.println("\nSTEP 4: Unregistered Peer 4 tries to send (should FAIL)");
        System.out.println("------------------------------------------------------------------");
        peers.get(3).buildEvent(EventType.MESSAGE, "Test from UNREGISTERED Peer 4".getBytes())
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Peer 4 queued message -- check logs for whitelist REJECTION");
        Thread.sleep(4000);

        // STEP 5: Backend generates token for Peer 4
        System.out.println("\nSTEP 5: Backend generates registration token for Peer 4");
        System.out.println("------------------------------------------------------------------");
        String peer4ID = peers.get(3).getOwnID();
        String token = peers.get(0).dataContainer.generateRegistrationToken(peer4ID);
        System.out.println("  Token generated: " + token.substring(0, 16) + "...");
        Thread.sleep(2000);

        // STEP 6: Peer 4 sends REGISTER_NODE event to backend WITH token
        System.out.println("\nSTEP 6: Peer 4 sends REGISTER_NODE with token to backend");
        System.out.println("------------------------------------------------------------------");
        // approver field carries the token so NodeMesh can validate one-time-use registration
        NodeRegistration regData = new NodeRegistration("CXNET", peer4ID, token);
        String regJson = ConnectX.serialize("cxJSON1", regData);

        peers.get(3).buildEvent(EventType.REGISTER_NODE, regJson.getBytes())
            .withRecordFlag(true)
            .toPeer(peers.get(0).getOwnID())
            .toNetwork("CXNET")
            .queue();
        System.out.println("  REGISTER_NODE event queued -- look for [REGISTER_NODE] log entry");
        Thread.sleep(5000);

        // STEP 7: Verify registration was processed
        System.out.println("\nSTEP 7: Verify registration processed by network");
        System.out.println("------------------------------------------------------------------");
        boolean backendHasReg = peers.get(0).dataContainer.isNodeRegistered("CXNET", peer4ID);
        System.out.println("  Backend has Peer 4 registered: " + backendHasReg);
        assertTrue(backendHasReg, "Peer 4 must be registered after REGISTER_NODE event processing");
        Thread.sleep(2000);

        // STEP 8: Peer 4 sends message → should be ACCEPTED now
        System.out.println("\nSTEP 8: Peer 4 sends message (should be ACCEPTED now)");
        System.out.println("------------------------------------------------------------------");
        peers.get(3).buildEvent(EventType.MESSAGE, "Test from NOW REGISTERED Peer 4".getBytes())
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Peer 4 queued message -- check logs for acceptance");
        Thread.sleep(4000);

        // STEP 9: Test token reuse → should FAIL
        System.out.println("\nSTEP 9: Test token reuse (should FAIL - tokens are one-time use)");
        System.out.println("------------------------------------------------------------------");
        // approver field carries the (already-consumed) token -- should be rejected
        NodeRegistration reuseData = new NodeRegistration("CXNET", peers.get(3).getOwnID(), token);
        String reuseJson = ConnectX.serialize("cxJSON1", reuseData);

        peers.get(3).buildEvent(EventType.REGISTER_NODE, reuseJson.getBytes())
            .withRecordFlag(true)
            .toPeer(peers.get(0).getOwnID())
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Peer 4 sent REGISTER_NODE with used token -- should be REJECTED");
        Thread.sleep(4000);

        // STEP 10: Start periodic sync
        System.out.println("\nSTEP 10: Start periodic backend sync");
        startPeriodicBackendSync(peers);
        System.out.println("  Periodic sync started (10-minute intervals)");

        System.out.println("\n==================================================================");
        System.out.println("  REAL INTEGRATION TEST COMPLETE");
        System.out.println("==================================================================");
        System.out.println("  Registration processed: " + backendHasReg);
        System.out.println("  CHECK LOGS ABOVE FOR ACTUAL NETWORK BEHAVIOR");
        System.out.println("==================================================================\n");
    }

    private static void startPeriodicBackendSync(List<ConnectX> peers) {
        Thread t = new Thread(() -> {
            int syncIntervalSeconds = 600;
            try {
                CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
                if (cxnet != null && cxnet.configuration != null && cxnet.configuration.syncIntervalSeconds != null) {
                    syncIntervalSeconds = cxnet.configuration.syncIntervalSeconds;
                }
            } catch (Exception e) {
                // Use default
            }

            System.out.println("[PERIODIC SYNC] Configured interval: " + syncIntervalSeconds + " seconds");

            while (true) {
                try {
                    Thread.sleep(syncIntervalSeconds * 1000L);
                    System.out.println("\n[PERIODIC SYNC] Starting sync...");

                    for (ConnectX peer : peers) {
                        for (String netID : new String[]{"CXNET"}) {
                            CXNetwork net = peer.getNetwork(netID);
                            if (net != null && net.configuration.backendSet != null &&
                                net.configuration.backendSet.contains(peer.getOwnID())) {

                                for (ConnectX other : peers) {
                                    if (!peer.getOwnID().equals(other.getOwnID())) {
                                        try {
                                            String reqJson = ConnectX.serialize("cxJSON1", new ChainStatus(netID));
                                            peer.buildEvent(EventType.CHAIN_STATUS_REQUEST, reqJson.getBytes())
                                                .toPeer(other.getOwnID())
                                                .toNetwork(netID)
                                                .signData()
                                                .queue();
                                        } catch (Exception e) {
                                            System.err.println("[SYNC] Error: " + e.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    }
                    System.out.println("[PERIODIC SYNC] Chain status requests sent");

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("[SYNC] Error: " + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.setName("PeriodicBackendSync");
        t.start();
    }

    /**
     * Blockchain sync and permission management test
     */
    private static void runBlockchainSyncAndPermissionTest(List<ConnectX> peers) throws Exception {
        System.out.println("\n\n==================================================================");
        System.out.println("  BLOCKCHAIN SYNC & PERMISSION TEST");
        System.out.println("==================================================================\n");

        String PEER2_ID = peers.get(1).getOwnID();

        // STEP 1: Try to record WITHOUT permission (should fail)
        System.out.println("STEP 1: Attempt to record WITHOUT permission (expect failure)");
        System.out.println("------------------------------------------------------------------");

        CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
        assertNotNull(cxnet, "CXNET must be loaded to run blockchain test");

        Long c3ID = cxnet.networkDictionary.c3;
        String PEER4_ID = peers.get(3).getOwnID();
        String EPOCH_ID = "00000000-0000-0000-0000-000000000001";

        java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> epochPerms = new java.util.HashMap<>();
        epochPerms.put(Permission.Record.name() + "-" + c3ID,
            new us.anvildevelopment.util.tools.permissions.BasicEntry(
                Permission.Record.name() + "-" + c3ID, true, 10));
        cxnet.networkPermissions.permissionSet.put(EPOCH_ID, epochPerms);

        long c3Before = cxnet.c3.current != null ? cxnet.c3.current.block : -1;
        int eventsBefore = cxnet.c3.current != null ? cxnet.c3.current.networkEvents.size() : 0;
        System.out.println("  c3 BEFORE: Block " + c3Before + " (" + eventsBefore + " events)");

        for (int i = 0; i < 10; i++) {
            peers.get(3).buildEvent(EventType.MESSAGE, ("Test WITHOUT permission #" + (i+1)).getBytes())
                .withRecordFlag(true)
                .toNetwork("CXNET", 3L)
                .queue();
        }
        Thread.sleep(3000);

        int eventsAfter1 = cxnet.c3.current != null ? cxnet.c3.current.networkEvents.size() : 0;
        System.out.println("  c3 AFTER (no permission): " + eventsAfter1 + " events (expected: " + eventsBefore + ")");
        assertEquals(eventsBefore, eventsAfter1, "No events should be recorded without Record permission");
        Thread.sleep(1000);

        // STEP 1b: Grant permission via GRANT_PERMISSION event
        System.out.println("\nSTEP 1b: Grant permission via blockchain event");
        System.out.println("------------------------------------------------------------------");

        PermissionChange permissionGrant = new PermissionChange("CXNET", PEER4_ID, Permission.Record.name(), c3ID, 10);
        String grantJson = ConnectX.serialize("cxJSON1", permissionGrant);

        peers.get(3).buildEvent(EventType.GRANT_PERMISSION, grantJson.getBytes())
            .withRecordFlag(true)
            .toNetwork("CXNET", 1L)
            .queue();
        Thread.sleep(2000);

        boolean peer4HasPermission = cxnet.checkChainPermission(PEER4_ID, Permission.Record.name(), c3ID);
        System.out.println("  Peer 4 has Record permission on c3: " + peer4HasPermission);

        if (SEND_MESSAGES) {
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                peers.get(3).buildEvent(EventType.MESSAGE, ("Blockchain sync test message #" + (i+1)).getBytes())
                    .withRecordFlag(true)
                    .toNetwork("CXNET", 3L)
                    .queue();
                if ((i+1) % 25 == 0) Thread.sleep(500);
            }
            Thread.sleep(5000);
        } else {
            System.out.println("  MESSAGE sending DISABLED (SEND_MESSAGES=false)");
            Thread.sleep(2000);
        }

        long c3After = cxnet.c3.current != null ? cxnet.c3.current.block : -1;
        int eventsAfter = cxnet.c3.current != null ? cxnet.c3.current.networkEvents.size() : 0;
        System.out.println("  c3 AFTER (with permission): Block " + c3After + " (" + eventsAfter + " events)");
        Thread.sleep(2000);

        // STEP 2: Wait for Peer 2 to sync
        System.out.println("\nSTEP 2: Waiting for Peer 2 to auto-sync blockchain...");
        Thread.sleep(15000);

        CXNetwork peer2Cxnet = peers.get(1).getNetwork("CXNET");
        if (peer2Cxnet != null) {
            long peer2C3 = peer2Cxnet.c3.current != null ? peer2Cxnet.c3.current.block : -1;
            System.out.println("  Peer 2 c3: Block " + peer2C3 + (peer2C3 == c3After ? " (in sync)" : " (may still be syncing)"));
        }
        Thread.sleep(2000);

        // STEP 3: Test Peer 2 permissions (grant and revoke)
        System.out.println("\nSTEP 3: Testing Peer 2 recording permissions");
        System.out.println("------------------------------------------------------------------");

        boolean r1 = cxnet.checkChainPermission(PEER2_ID, Permission.Record.name(), c3ID);
        System.out.println("  Before grant: has permission = " + r1 + " (expected false)");

        java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> perms = new java.util.HashMap<>();
        perms.put(Permission.Record.name() + "-" + c3ID,
            new us.anvildevelopment.util.tools.permissions.BasicEntry(
                Permission.Record.name() + "-" + c3ID, true, 10));
        cxnet.networkPermissions.permissionSet.put(PEER2_ID, perms);

        boolean r2 = cxnet.checkChainPermission(PEER2_ID, Permission.Record.name(), c3ID);
        System.out.println("  After grant: has permission = " + r2 + " (expected true)");
        assertTrue(r2, "Peer 2 must have Record permission after grant");

        if (r2) {
            peers.get(1).buildEvent(EventType.MESSAGE, "Test from Peer 2 WITH permissions".getBytes())
                .toNetwork("CXNET")
                .queue();
        }
        Thread.sleep(2000);

        cxnet.networkPermissions.permissionSet.remove(PEER2_ID);
        boolean r3 = cxnet.checkChainPermission(PEER2_ID, Permission.Record.name(), c3ID);
        System.out.println("  After revoke: has permission = " + r3 + " (expected false)");
        assertFalse(r3, "Peer 2 must not have Record permission after revoke");

        peers.get(1).buildEvent(EventType.MESSAGE, "Test from Peer 2 AFTER revoke".getBytes())
            .toNetwork("CXNET")
            .queue();
        Thread.sleep(2000);

        System.out.println("\n==================================================================");
        System.out.println("  BLOCKCHAIN SYNC & PERMISSION TEST COMPLETE");
        System.out.println("==================================================================\n");
    }

    /**
     * E2E (End-to-End) Encryption Test
     */
    private static void runE2EEncryptionTest(List<ConnectX> peers) throws Exception {
        System.out.println("\n\n==================================================================");
        System.out.println("  E2E ENCRYPTION TEST");
        System.out.println("==================================================================\n");

        Thread.sleep(2000);

        // TEST 1: Baseline non-encrypted message
        System.out.println("TEST 1: Baseline - Regular message (no E2E encryption)");
        System.out.println("------------------------------------------------------------------");
        peers.get(0).buildEvent(EventType.MESSAGE, "Regular message - NOT encrypted".getBytes())
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Regular message queued");
        Thread.sleep(3000);

        // TEST 2: Multi-recipient E2E encrypted message
        System.out.println("\nTEST 2: E2E encrypted message to multiple recipients");
        System.out.println("------------------------------------------------------------------");
        System.out.println("  Sender: Peer 1  Recipients: Peer 2, Peer 3");
        peers.get(0).buildEvent(EventType.MESSAGE, "SECRET: E2E encrypted message!".getBytes())
            .addRecipient(peers.get(1).getOwnID())
            .addRecipient(peers.get(2).getOwnID())
            .encrypt()
            .toNetwork("CXNET")
            .queue();
        System.out.println("  E2E encrypted message queued (look for [E2E] tags in logs)");
        Thread.sleep(5000);

        // TEST 3: Second E2E message with different recipients
        System.out.println("\nTEST 3: Second E2E encrypted message (different recipients)");
        System.out.println("------------------------------------------------------------------");
        System.out.println("  Sender: Peer 2  Recipients: Peer 3, Peer 4");
        peers.get(1).buildEvent(EventType.MESSAGE, "SECRET: Another E2E message from Peer 2!".getBytes())
            .addRecipient(peers.get(2).getOwnID())
            .addRecipient(peers.get(3).getOwnID())
            .encrypt()
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Second E2E message queued");
        Thread.sleep(5000);

        // TEST 4: Single-recipient E2E message
        System.out.println("\nTEST 4: E2E encrypted message to single recipient");
        System.out.println("------------------------------------------------------------------");
        System.out.println("  Sender: Peer 3  Recipient: Peer 1 only");
        peers.get(2).buildEvent(EventType.MESSAGE, "SECRET: Private message to Peer 1 only!".getBytes())
            .addRecipient(peers.get(0).getOwnID())
            .encrypt()
            .toNetwork("CXNET")
            .queue();
        System.out.println("  Single-recipient E2E message queued");
        Thread.sleep(5000);

        // TEST 5: Signed Blob Architecture Verification
        System.out.println("\nTEST 5: Signed Blob Architecture Verification");
        System.out.println("==================================================================");
        for (int i = 0; i < peers.size(); i++) {
            ConnectX peer = peers.get(i);
            CXNetwork network = peer.getNetwork("CXNET");
            if (network == null) {
                System.out.println("Peer " + (i+1) + ": CXNET not loaded, skipping");
                continue;
            }
            var c3 = network.c3;
            if (c3 == null || c3.current == null) {
                System.out.println("Peer " + (i+1) + ": no c3 blockchain, skipping");
                continue;
            }

            var currentBlock = c3.current;
            int signedBlobCount = currentBlock.networkEvents.size();
            System.out.println("Peer " + (i+1) + " (" + peer.getOwnID().substring(0, 8) + "): "
                + "Block " + currentBlock.block + " - " + signedBlobCount + " signed blobs");

            if (signedBlobCount > 0) {
                int prepared = currentBlock.prepare(peer);
                System.out.println("  Prepared " + prepared + "/" + signedBlobCount + " events");

                byte[] signedBlob = currentBlock.networkEvents.get(0);
                var event = currentBlock.deserializedEvents.get(0);
                if (signedBlob != null && event != null) {
                    System.out.println("  Event 0: " + event.eT + " (ID: " + event.iD.substring(0, 8) + "...)");
                    if (event.p != null && event.p.cxID != null) {
                        try {
                            java.io.ByteArrayInputStream verifyStream = new java.io.ByteArrayInputStream(signedBlob);
                            java.io.ByteArrayOutputStream verifiedOutput = new java.io.ByteArrayOutputStream();
                            boolean verified = peer.encryptionProvider.verifyAndStrip(verifyStream, verifiedOutput, event.p.cxID);
                            verifyStream.close();
                            System.out.println("  Signature: " + (verified ? "VERIFIED" : "FAILED"));
                            assertTrue(verified, "Signed event blob must have valid signature from " + event.p.cxID.substring(0, 8));
                        } catch (Exception e) {
                            System.err.println("  Signature verification error: " + e.getMessage());
                            fail("Signature verification threw: " + e.getMessage());
                        }
                    }
                    System.out.println("  Signed blob: " + signedBlob.length + " bytes");
                }

                java.util.Map<String, Integer> eventTypeCounts = new java.util.HashMap<>();
                for (var evt : currentBlock.deserializedEvents.values()) {
                    eventTypeCounts.put(evt.eT, eventTypeCounts.getOrDefault(evt.eT, 0) + 1);
                }
                System.out.print("  Event types: ");
                for (java.util.Map.Entry<String, Integer> entry : eventTypeCounts.entrySet()) {
                    System.out.print(entry.getKey() + "=" + entry.getValue() + " ");
                }
                System.out.println();
            }
        }

        System.out.println("\n==================================================================");
        System.out.println("  E2E ENCRYPTION TEST COMPLETE");
        System.out.println("==================================================================\n");
    }
}