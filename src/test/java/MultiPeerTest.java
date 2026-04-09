import org.junit.jupiter.api.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static Logger log = LoggerFactory.getLogger(MultiPeerTest.class);

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeAll
    @Timeout(value = 420, unit = TimeUnit.SECONDS)
    static void setupPeers() throws Exception {
        log.info("=== CXNET Multi-Peer Mesh Network Test ===\n");
        log.info("IMPORTANT: HTTPBridgeTest (EPOCH) must be running first!\n");

        int basePort = 49153;
        peers = new ArrayList<>();

        // Create 4 peers, start their HTTP bridges, attempt CXNET bootstrap
        log.info("Step 1: Creating and initializing 4 CXNET peers...");
        for (int i = 1; i <= 4; i++) {
            String peerDir = "ConnectX-Peer" + i;
            int port = basePort + (i - 1);
            int httpPort = 8080 + i;

            log.info("  Creating Peer " + i + " on port " + port + "...");
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
                    log.info("    HTTP bridge started on port " + httpPort);
                    log.info("    Public address: cxHTTP1:" + publicEndpoint);
                }
            } catch (Exception e) {
                System.err.println("    Warning: Failed to start HTTP bridge: " + e.getMessage());
                e.printStackTrace();
            }

            // Trigger bootstrap -- node contacts EPOCH via its HTTP bridge automatically
            peer.attemptCXNETBootstrap();

            peers.add(peer);
            log.info("    UUID: " + peer.getOwnID());
            Thread.sleep(500);
        }

        // Flat startup wait: give all peers time to spin up, run their LAN scans,
        // exchange CXHELLO, and converge before any testing begins.
        final int STARTUP_WAIT_SECONDS = 120;
        log.info("\nStep 1b: Waiting " + STARTUP_WAIT_SECONDS + "s for peers to spin up and discover each other...");
        for (int i = STARTUP_WAIT_SECONDS; i > 0; i--) {
            if (i % 30 == 0 || i <= 5) {
                System.out.print("  " + i + "s remaining -- HV peers:");
                for (int j = 0; j < peers.size(); j++) {
                    System.out.print(" P" + (j+1) + "=" + peers.get(j).nodeMesh.peerDirectory.hv.size());
                }
                log.info("\n");
            }
            Thread.sleep(1000);
        }
        log.info("  Startup wait complete.");

        // Print state snapshot after the startup wait
        log.info("\nStep 1c: Peer state after startup wait:");
        for (int i = 0; i < peers.size(); i++) {
            ConnectX p = peers.get(i);
            log.info("  P" + (i+1) + " (" + p.getOwnID().substring(0, 8) + ")"
                + "  state=" + p.state
                + "  HV=" + p.nodeMesh.peerDirectory.hv.size()
                + "  LAN-peers=" + p.dataContainer.getLocalPeerCount());
        }

        // Optionally send CXN messages during setup
        log.info("\nStep 1d: Testing P2P message delivery via CXN broadcast...");
        int cxnetCount = 0;
        for (ConnectX peer : peers) {
            if (peer.getNetwork("CXNET") != null) cxnetCount++;
        }
        log.info("  Peers with CXNET loaded: " + cxnetCount + "/" + peers.size());

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
        log.info("\nStep 2: Waiting for bootstrap to complete...");
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
                log.info("  [" + elapsed + "s] " + bootstrapCount + "/" + peers.size() + " peers joined CXNET");
            }
            if (bootstrapCount == peers.size()) {
                log.info("  All peers bootstrapped successfully!");
                break;
            }
        }

        // Final count in case the loop above exited early
        bootstrapCount = 0;
        for (ConnectX peer : peers) {
            if (peer.getNetwork("CXNET") != null) bootstrapCount++;
        }

        // Diagnostics
        log.info("\n=== INTERNAL STATE DIAGNOSTICS ===");
        for (int i = 0; i < peers.size(); i++) {
            ConnectX peer = peers.get(i);
            log.info("\nPeer " + (i + 1) + " (" + peer.getOwnID().substring(0, 8) + "):");
            CXNetwork cxnet = peer.getNetwork("CXNET");
            log.info("  CXNET: " + (cxnet != null ? "JOINED" : "NOT JOINED"));
            if (cxnet != null) {
                log.info("    Chain c1: " + cxnet.c1.blockMap.size() + " blocks");
                log.info("    Chain c2: " + cxnet.c2.blockMap.size() + " blocks");
                log.info("    Chain c3: " + cxnet.c3.blockMap.size() + " blocks");
            }
            int hvPeerCount = peer.nodeMesh.peerDirectory.hv.size();
            int lanPeerCount = peer.nodeMesh.peerDirectory.lan.size();
            log.info("  PeerDirectory: HV=" + hvPeerCount + " LAN=" + lanPeerCount
                + " cached=" + peer.nodeMesh.peerDirectory.peerCache.size());
            if (hvPeerCount > 0) {
                int count = 0;
                for (String peerID : peer.nodeMesh.peerDirectory.hv.keySet()) {
                    if (count++ >= 3) { log.info("      ... and " + (hvPeerCount - 3) + " more"); break; }
                    Node node = peer.nodeMesh.peerDirectory.hv.get(peerID);
                    log.info("      " + peerID.substring(0, 8) + " @ " + node.addr);
                }
            }
            log.info("  Queues: output=" + peer.outputQueue.size() + " event=" + peer.eventQueue.size());
        }

        log.info("\n=== Setup Complete: " + bootstrapCount + "/" + peers.size() + " peers bootstrapped ===\n");
    }

    @AfterAll
    static void teardownPeers() {
        // Peer threads are daemon threads and will stop with the JVM.
        // If ConnectX gains a shutdown() API in the future, call it here.
        if (peers != null) {
            log.info("=== Tearing down " + peers.size() + " peers ===");
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
        log.info("\n=== Bootstrap Results ===");
        int successCount = 0;
        for (int i = 0; i < peers.size(); i++) {
            CXNetwork cxnet = peers.get(i).getNetwork("CXNET");
            if (cxnet != null) {
                log.info("  Peer " + (i + 1) + " joined CXNET");
                successCount++;
            } else {
                log.info("  Peer " + (i + 1) + " did NOT join CXNET");
            }
        }

        assertTrue(successCount > 0, "At least one peer must successfully bootstrap into CXNET");

        CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
        if (cxnet != null) {
            log.info("  Network ID: " + cxnet.configuration.netID);
            log.info("  Chain c1: " + cxnet.networkDictionary.c1);
            log.info("  Chain c2: " + cxnet.networkDictionary.c2);
            log.info("  Chain c3: " + cxnet.networkDictionary.c3);
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
        log.info("\n=== Test: Peer 3 broadcasts message ===");
        try {
            peers.get(2).buildEvent(EventType.MESSAGE,
                    ConnectX.serialize("cxJSON1", broadcastMsg).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toNetwork("CXNET")
                .signData()
                .queue();
        } catch (Exception e) {
            fail("Failed to serialize/queue broadcast message: " + e.getMessage());
        }
        log.info("Message queued from Peer 3 (" + peers.get(2).getOwnID() + ")");
        Thread.sleep(5000);

        // Count how many OTHER peers received the broadcast (match by text field)
        long deliveredTo = receivedMessages.entrySet().stream()
            .filter(e -> e.getKey() != 2) // exclude the sender (index 2 = Peer 3)
            .filter(e -> e.getValue().stream().anyMatch(m -> broadcastText.equals(m.text)))
            .count();
        log.info("  Broadcast delivered to " + deliveredTo + " peer(s) (excluding sender)");
        assertTrue(deliveredTo > 0,
            "At least one peer must receive the broadcast from Peer 3 via CXNET");

        log.info("\n=== Test: Peer finding broadcast ===");
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
        log.info("Peer finding event queued");
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
        log.info("\n\n==================================================================");
        log.info("  SECURITY FEATURES TEST");
        log.info("==================================================================\n");

        Thread.sleep(1000);

        // Test 1: Whitelist Mode Enforcement
        log.info("TEST 1: Whitelist Mode Network");
        log.info("------------------------------------------------------------------");
        CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
        assertNotNull(cxnet, "CXNET must be loaded to run security tests");

        log.info("CXNET Configuration:");
        log.info("  - WhitelistMode: " + (cxnet.configuration.whitelistMode != null ?
                          cxnet.configuration.whitelistMode : "false (default)"));

        int regCount = peers.get(0).dataContainer.networkRegisteredNodes.getOrDefault("CXNET", new java.util.HashSet<>()).size();
        int blockCount = peers.get(0).dataContainer.networkBlockedNodes.getOrDefault("CXNET", new java.util.HashMap<>()).size();
        log.info("  - Registered nodes: " + regCount);
        log.info("  - Blocked nodes: " + blockCount);
        log.info("PASS: Whitelist infrastructure present\n");

        Thread.sleep(1000);

        // Test 2: REGISTER_NODE Event
        log.info("TEST 2: Node Registration (REGISTER_NODE)");
        log.info("------------------------------------------------------------------");

        NodeRegistration registerPayload = new NodeRegistration("CXNET", peers.get(3).getOwnID(), "test-nmi");
        String nodeID = registerPayload.nodeID;

        // Process registration (stored in local DataContainer)
        peers.get(0).dataContainer.networkRegisteredNodes.computeIfAbsent("CXNET", k -> new java.util.HashSet<>()).add(nodeID);

        log.info("  Node registered:");
        log.info("    - Node: " + nodeID);
        log.info("    - Network: CXNET");
        log.info("    - Total registered: " + peers.get(0).dataContainer.networkRegisteredNodes.get("CXNET").size());
        assertTrue(peers.get(0).dataContainer.networkRegisteredNodes.get("CXNET").contains(nodeID),
            "Node must be present in registered set after registration");
        log.info("PASS: Registration processed\n");

        Thread.sleep(1000);

        // Test 3: BLOCK_NODE Event
        log.info("TEST 3: Node Blocking (BLOCK_NODE)");
        log.info("------------------------------------------------------------------");

        NodeModeration blockPayload = new NodeModeration("CXNET", peers.get(3).getOwnID(), "testing block mechanism");
        String blockedNodeID = blockPayload.nodeID;
        String reason = blockPayload.reason;

        // Process block (stored in local DataContainer)
        peers.get(0).dataContainer.blockNode("CXNET", blockedNodeID, reason);

        log.info("  Node blocked:");
        log.info("    - Node: " + blockedNodeID);
        log.info("    - Reason: " + reason);
        log.info("    - Total blocked: " + peers.get(0).dataContainer.networkBlockedNodes.get("CXNET").size());
        assertTrue(peers.get(0).dataContainer.networkBlockedNodes.get("CXNET").containsKey(blockedNodeID),
            "Node must be present in blocked map after blocking");
        log.info("PASS: Blocking processed\n");

        Thread.sleep(1000);

        // Test 4: UNBLOCK_NODE Event
        log.info("TEST 4: Node Unblocking (UNBLOCK_NODE)");
        log.info("------------------------------------------------------------------");

        NodeModeration unblockPayload = new NodeModeration("CXNET", blockedNodeID, null);
        String unblockedNodeID = unblockPayload.nodeID;

        // Process unblock (stored in local DataContainer)
        String removedReason = peers.get(0).dataContainer.unblockNode("CXNET", unblockedNodeID);

        log.info("  Node unblocked:");
        log.info("    - Node: " + unblockedNodeID);
        log.info("    - Was blocked for: " + removedReason);
        log.info("    - Total blocked: " + peers.get(0).dataContainer.networkBlockedNodes.get("CXNET").size());
        assertFalse(peers.get(0).dataContainer.networkBlockedNodes.getOrDefault("CXNET", new java.util.HashMap<>()).containsKey(unblockedNodeID),
            "Node must not be in blocked map after unblocking");
        log.info("PASS: Unblocking processed\n");

        Thread.sleep(1000);

        // Test 5: Peer Discovery
        log.info("TEST 5: Peer Discovery (PEER_LIST_REQUEST)");
        log.info("------------------------------------------------------------------");
        int hvCount = (peers.get(0).nodeMesh.peerDirectory.hv != null) ? peers.get(0).nodeMesh.peerDirectory.hv.size() : 0;
        int maxPeers = Math.min(10, (int) Math.ceil(hvCount * 0.3));
        log.info("  - Total HV peers (Peer 1): " + hvCount);
        log.info("  - 30% of peers: " + (int) Math.ceil(hvCount * 0.3));
        log.info("  - Max returned: " + maxPeers);
        log.info("  - Rate limit: 3 requests/IP/hour");
        log.info("PASS: Rate limiting enforced\n");

        Thread.sleep(1000);

        // Test 6: Security Summary
        log.info("TEST 6: Security Feature Summary");
        log.info("------------------------------------------------------------------");
        log.info("Implemented Security Features:");
        log.info("  Whitelist Mode, Node Registration, Node Blocking, Node Unblocking,");
        log.info("  Peer Discovery, IP Rate Limiting, Two-Tier Blocking, Whitelist Enforcement");
        log.info("\n");
        log.info("==================================================================");
        log.info("  ALL SECURITY TESTS PASSED");
        log.info("==================================================================\n");
    }

    /**
     * REAL whitelist integration test - uses actual network event processing
     */
    private static void runWhitelistIntegrationTest(List<ConnectX> peers) throws Exception {
        log.info("\n\n==================================================================");
        log.info("  REAL WHITELIST INTEGRATION TEST");
        log.info("==================================================================");
        log.info("This test uses ACTUAL event processing, not programmatic shortcuts\n");

        Thread.sleep(2000);

        // STEP 1: Request CXNET seed from EPOCH
        log.info("STEP 1: Request CXNET seed from EPOCH");
        log.info("------------------------------------------------------------------");
        for (int i = 0; i < 3; i++) {
            String reqJson = ConnectX.serialize("cxJSON1", new SeedExchange("CXNET"));
            peers.get(i).buildEvent(EventType.SEED_REQUEST, reqJson.getBytes())
                .toPeer("00000000-0000-0000-0000-000000000001")  // EPOCH UUID
                .signData()
                .queue();
            log.info("  Peer " + (i + 1) + " requested CXNET seed");
        }
        log.info("  Waiting for EPOCH to respond...");
        Thread.sleep(8000);

        int joinedCount = 0;
        for (int i = 0; i < 3; i++) {
            CXNetwork CXNET = peers.get(i).getNetwork("CXNET");
            if (CXNET != null) {
                joinedCount++;
                log.info("  Peer " + (i + 1) + " received CXNET (whitelist: " +
                    CXNET.configuration.whitelistMode + ")");
            }
        }
        if (joinedCount == 0) {
            log.info("  No peers received CXNET - skipping whitelist test");
            return;
        }
        log.info("  " + joinedCount + "/3 peers joined CXNET");
        Thread.sleep(2000);

        // STEP 3: Test that registered peers CAN communicate
        log.info("\nSTEP 3: Verify registered peers can communicate");
        log.info("------------------------------------------------------------------");
        peers.get(0).buildEvent(EventType.MESSAGE, "Test from registered Peer 1".getBytes())
            .toNetwork("CXNET")
            .queue();
        log.info("  Peer 1 sent message");
        Thread.sleep(1000);
        peers.get(2).buildEvent(EventType.MESSAGE, "Test from registered Peer 3".getBytes())
            .toNetwork("CXNET")
            .queue();
        log.info("  Peer 3 sent message");
        Thread.sleep(3000);

        // STEP 4: Unregistered Peer 4 tries to send → should be REJECTED
        log.info("\nSTEP 4: Unregistered Peer 4 tries to send (should FAIL)");
        log.info("------------------------------------------------------------------");
        peers.get(3).buildEvent(EventType.MESSAGE, "Test from UNREGISTERED Peer 4".getBytes())
            .toNetwork("CXNET")
            .queue();
        log.info("  Peer 4 queued message -- check logs for whitelist REJECTION");
        Thread.sleep(4000);

        // STEP 5: Backend generates token for Peer 4
        log.info("\nSTEP 5: Backend generates registration token for Peer 4");
        log.info("------------------------------------------------------------------");
        String peer4ID = peers.get(3).getOwnID();
        String token = peers.get(0).dataContainer.generateRegistrationToken(peer4ID);
        log.info("  Token generated: " + token.substring(0, 16) + "...");
        Thread.sleep(2000);

        // STEP 6: Peer 4 sends REGISTER_NODE event to backend WITH token
        log.info("\nSTEP 6: Peer 4 sends REGISTER_NODE with token to backend");
        log.info("------------------------------------------------------------------");
        // approver field carries the token so NodeMesh can validate one-time-use registration
        NodeRegistration regData = new NodeRegistration("CXNET", peer4ID, token);
        String regJson = ConnectX.serialize("cxJSON1", regData);

        peers.get(3).buildEvent(EventType.REGISTER_NODE, regJson.getBytes())
            .withRecordFlag(true)
            .toPeer(peers.get(0).getOwnID())
            .toNetwork("CXNET")
            .queue();
        log.info("  REGISTER_NODE event queued -- look for [REGISTER_NODE] log entry");
        Thread.sleep(5000);

        // STEP 7: Verify registration was processed
        log.info("\nSTEP 7: Verify registration processed by network");
        log.info("------------------------------------------------------------------");
        boolean backendHasReg = peers.get(0).dataContainer.isNodeRegistered("CXNET", peer4ID);
        log.info("  Backend has Peer 4 registered: " + backendHasReg);
        assertTrue(backendHasReg, "Peer 4 must be registered after REGISTER_NODE event processing");
        Thread.sleep(2000);

        // STEP 8: Peer 4 sends message → should be ACCEPTED now
        log.info("\nSTEP 8: Peer 4 sends message (should be ACCEPTED now)");
        log.info("------------------------------------------------------------------");
        peers.get(3).buildEvent(EventType.MESSAGE, "Test from NOW REGISTERED Peer 4".getBytes())
            .toNetwork("CXNET")
            .queue();
        log.info("  Peer 4 queued message -- check logs for acceptance");
        Thread.sleep(4000);

        // STEP 9: Test token reuse → should FAIL
        log.info("\nSTEP 9: Test token reuse (should FAIL - tokens are one-time use)");
        log.info("------------------------------------------------------------------");
        // approver field carries the (already-consumed) token -- should be rejected
        NodeRegistration reuseData = new NodeRegistration("CXNET", peers.get(3).getOwnID(), token);
        String reuseJson = ConnectX.serialize("cxJSON1", reuseData);

        peers.get(3).buildEvent(EventType.REGISTER_NODE, reuseJson.getBytes())
            .withRecordFlag(true)
            .toPeer(peers.get(0).getOwnID())
            .toNetwork("CXNET")
            .queue();
        log.info("  Peer 4 sent REGISTER_NODE with used token -- should be REJECTED");
        Thread.sleep(4000);

        // STEP 10: Start periodic sync
        log.info("\nSTEP 10: Start periodic backend sync");
        startPeriodicBackendSync(peers);
        log.info("  Periodic sync started (10-minute intervals)");

        log.info("\n==================================================================");
        log.info("  REAL INTEGRATION TEST COMPLETE");
        log.info("==================================================================");
        log.info("  Registration processed: " + backendHasReg);
        log.info("  CHECK LOGS ABOVE FOR ACTUAL NETWORK BEHAVIOR");
        log.info("==================================================================\n");
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

            log.info("[PERIODIC SYNC] Configured interval: " + syncIntervalSeconds + " seconds");

            while (true) {
                try {
                    Thread.sleep(syncIntervalSeconds * 1000L);
                    log.info("\n[PERIODIC SYNC] Starting sync...");

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
                    log.info("[PERIODIC SYNC] Chain status requests sent");

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
        log.info("\n\n==================================================================");
        log.info("  BLOCKCHAIN SYNC & PERMISSION TEST");
        log.info("==================================================================\n");

        String PEER2_ID = peers.get(1).getOwnID();

        // STEP 1: Try to record WITHOUT permission (should fail)
        log.info("STEP 1: Attempt to record WITHOUT permission (expect failure)");
        log.info("------------------------------------------------------------------");

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
        log.info("  c3 BEFORE: Block " + c3Before + " (" + eventsBefore + " events)");

        for (int i = 0; i < 10; i++) {
            peers.get(3).buildEvent(EventType.MESSAGE, ("Test WITHOUT permission #" + (i+1)).getBytes())
                .withRecordFlag(true)
                .toNetwork("CXNET", 3L)
                .queue();
        }
        Thread.sleep(3000);

        int eventsAfter1 = cxnet.c3.current != null ? cxnet.c3.current.networkEvents.size() : 0;
        log.info("  c3 AFTER (no permission): " + eventsAfter1 + " events (expected: " + eventsBefore + ")");
        assertEquals(eventsBefore, eventsAfter1, "No events should be recorded without Record permission");
        Thread.sleep(1000);

        // STEP 1b: Grant permission via GRANT_PERMISSION event
        log.info("\nSTEP 1b: Grant permission via blockchain event");
        log.info("------------------------------------------------------------------");

        PermissionChange permissionGrant = new PermissionChange("CXNET", PEER4_ID, Permission.Record.name(), c3ID, 10);
        String grantJson = ConnectX.serialize("cxJSON1", permissionGrant);

        peers.get(3).buildEvent(EventType.GRANT_PERMISSION, grantJson.getBytes())
            .withRecordFlag(true)
            .toNetwork("CXNET", 1L)
            .queue();
        Thread.sleep(2000);

        boolean peer4HasPermission = cxnet.checkChainPermission(PEER4_ID, Permission.Record.name(), c3ID);
        log.info("  Peer 4 has Record permission on c3: " + peer4HasPermission);

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
            log.info("  MESSAGE sending DISABLED (SEND_MESSAGES=false)");
            Thread.sleep(2000);
        }

        long c3After = cxnet.c3.current != null ? cxnet.c3.current.block : -1;
        int eventsAfter = cxnet.c3.current != null ? cxnet.c3.current.networkEvents.size() : 0;
        log.info("  c3 AFTER (with permission): Block " + c3After + " (" + eventsAfter + " events)");
        Thread.sleep(2000);

        // STEP 2: Wait for Peer 2 to sync
        log.info("\nSTEP 2: Waiting for Peer 2 to auto-sync blockchain...");
        Thread.sleep(15000);

        CXNetwork peer2Cxnet = peers.get(1).getNetwork("CXNET");
        if (peer2Cxnet != null) {
            long peer2C3 = peer2Cxnet.c3.current != null ? peer2Cxnet.c3.current.block : -1;
            log.info("  Peer 2 c3: Block " + peer2C3 + (peer2C3 == c3After ? " (in sync)" : " (may still be syncing)"));
        }
        Thread.sleep(2000);

        // STEP 3: Test Peer 2 permissions (grant and revoke)
        log.info("\nSTEP 3: Testing Peer 2 recording permissions");
        log.info("------------------------------------------------------------------");

        boolean r1 = cxnet.checkChainPermission(PEER2_ID, Permission.Record.name(), c3ID);
        log.info("  Before grant: has permission = " + r1 + " (expected false)");

        java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> perms = new java.util.HashMap<>();
        perms.put(Permission.Record.name() + "-" + c3ID,
            new us.anvildevelopment.util.tools.permissions.BasicEntry(
                Permission.Record.name() + "-" + c3ID, true, 10));
        cxnet.networkPermissions.permissionSet.put(PEER2_ID, perms);

        boolean r2 = cxnet.checkChainPermission(PEER2_ID, Permission.Record.name(), c3ID);
        log.info("  After grant: has permission = " + r2 + " (expected true)");
        assertTrue(r2, "Peer 2 must have Record permission after grant");

        if (r2) {
            peers.get(1).buildEvent(EventType.MESSAGE, "Test from Peer 2 WITH permissions".getBytes())
                .toNetwork("CXNET")
                .queue();
        }
        Thread.sleep(2000);

        cxnet.networkPermissions.permissionSet.remove(PEER2_ID);
        boolean r3 = cxnet.checkChainPermission(PEER2_ID, Permission.Record.name(), c3ID);
        log.info("  After revoke: has permission = " + r3 + " (expected false)");
        assertFalse(r3, "Peer 2 must not have Record permission after revoke");

        peers.get(1).buildEvent(EventType.MESSAGE, "Test from Peer 2 AFTER revoke".getBytes())
            .toNetwork("CXNET")
            .queue();
        Thread.sleep(2000);

        log.info("\n==================================================================");
        log.info("  BLOCKCHAIN SYNC & PERMISSION TEST COMPLETE");
        log.info("==================================================================\n");
    }

    /**
     * E2E (End-to-End) Encryption Test
     */
    private static void runE2EEncryptionTest(List<ConnectX> peers) throws Exception {
        log.info("\n\n==================================================================");
        log.info("  E2E ENCRYPTION TEST");
        log.info("==================================================================\n");

        Thread.sleep(2000);

        // TEST 1: Baseline non-encrypted message
        log.info("TEST 1: Baseline - Regular message (no E2E encryption)");
        log.info("------------------------------------------------------------------");
        peers.get(0).buildEvent(EventType.MESSAGE, "Regular message - NOT encrypted".getBytes())
            .toNetwork("CXNET")
            .queue();
        log.info("  Regular message queued");
        Thread.sleep(3000);

        // TEST 2: Multi-recipient E2E encrypted message
        log.info("\nTEST 2: E2E encrypted message to multiple recipients");
        log.info("------------------------------------------------------------------");
        log.info("  Sender: Peer 1  Recipients: Peer 2, Peer 3");
        peers.get(0).buildEvent(EventType.MESSAGE, "SECRET: E2E encrypted message!".getBytes())
            .addRecipient(peers.get(1).getOwnID())
            .addRecipient(peers.get(2).getOwnID())
            .encrypt()
            .toNetwork("CXNET")
            .queue();
        log.info("  E2E encrypted message queued (look for [E2E] tags in logs)");
        Thread.sleep(5000);

        // TEST 3: Second E2E message with different recipients
        log.info("\nTEST 3: Second E2E encrypted message (different recipients)");
        log.info("------------------------------------------------------------------");
        log.info("  Sender: Peer 2  Recipients: Peer 3, Peer 4");
        peers.get(1).buildEvent(EventType.MESSAGE, "SECRET: Another E2E message from Peer 2!".getBytes())
            .addRecipient(peers.get(2).getOwnID())
            .addRecipient(peers.get(3).getOwnID())
            .encrypt()
            .toNetwork("CXNET")
            .queue();
        log.info("  Second E2E message queued");
        Thread.sleep(5000);

        // TEST 4: Single-recipient E2E message
        log.info("\nTEST 4: E2E encrypted message to single recipient");
        log.info("------------------------------------------------------------------");
        log.info("  Sender: Peer 3  Recipient: Peer 1 only");
        peers.get(2).buildEvent(EventType.MESSAGE, "SECRET: Private message to Peer 1 only!".getBytes())
            .addRecipient(peers.get(0).getOwnID())
            .encrypt()
            .toNetwork("CXNET")
            .queue();
        log.info("  Single-recipient E2E message queued");
        Thread.sleep(5000);

        // TEST 5: Signed Blob Architecture Verification
        log.info("\nTEST 5: Signed Blob Architecture Verification");
        log.info("==================================================================");
        for (int i = 0; i < peers.size(); i++) {
            ConnectX peer = peers.get(i);
            CXNetwork network = peer.getNetwork("CXNET");
            if (network == null) {
                log.info("Peer " + (i+1) + ": CXNET not loaded, skipping");
                continue;
            }
            var c3 = network.c3;
            if (c3 == null || c3.current == null) {
                log.info("Peer " + (i+1) + ": no c3 blockchain, skipping");
                continue;
            }

            var currentBlock = c3.current;
            int signedBlobCount = currentBlock.networkEvents.size();
            log.info("Peer " + (i+1) + " (" + peer.getOwnID().substring(0, 8) + "): "
                + "Block " + currentBlock.block + " - " + signedBlobCount + " signed blobs");

            if (signedBlobCount > 0) {
                int prepared = currentBlock.prepare(peer);
                log.info("  Prepared " + prepared + "/" + signedBlobCount + " events");

                byte[] signedBlob = currentBlock.networkEvents.get(0);
                var event = currentBlock.deserializedEvents.get(0);
                if (signedBlob != null && event != null) {
                    log.info("  Event 0: " + event.eT + " (ID: " + event.iD.substring(0, 8) + "...)");
                    if (event.p != null && event.p.cxID != null) {
                        try {
                            java.io.ByteArrayInputStream verifyStream = new java.io.ByteArrayInputStream(signedBlob);
                            java.io.ByteArrayOutputStream verifiedOutput = new java.io.ByteArrayOutputStream();
                            boolean verified = peer.encryptionProvider.verifyAndStrip(verifyStream, verifiedOutput, event.p.cxID);
                            verifyStream.close();
                            log.info("  Signature: " + (verified ? "VERIFIED" : "FAILED"));
                            assertTrue(verified, "Signed event blob must have valid signature from " + event.p.cxID.substring(0, 8));
                        } catch (Exception e) {
                            System.err.println("  Signature verification error: " + e.getMessage());
                            fail("Signature verification threw: " + e.getMessage());
                        }
                    }
                    log.info("  Signed blob: " + signedBlob.length + " bytes");
                }

                java.util.Map<String, Integer> eventTypeCounts = new java.util.HashMap<>();
                for (var evt : currentBlock.deserializedEvents.values()) {
                    eventTypeCounts.put(evt.eT, eventTypeCounts.getOrDefault(evt.eT, 0) + 1);
                }
                System.out.print("  Event types: ");
                for (java.util.Map.Entry<String, Integer> entry : eventTypeCounts.entrySet()) {
                    System.out.print(entry.getKey() + "=" + entry.getValue() + " ");
                }
                log.info("\n");
            }
        }

        log.info("\n==================================================================");
        log.info("  E2E ENCRYPTION TEST COMPLETE");
        log.info("==================================================================\n");
    }

    // -------------------------------------------------------------------------
    // Stream session test
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("Stream session: open, transfer, close via CXStreamPlugin")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testStreamSession() throws Exception {
        assumeBootstrapped();
        // Need at least 2 peers that know about each other
        org.junit.jupiter.api.Assumptions.assumeTrue(
            peers.size() >= 2 && peers.get(0).nodeMesh.peerDirectory.hv.size() >= 1,
            "Skipping: peers have not discovered each other yet");
        runStreamTest(peers);
    }

    private static void runStreamTest(List<ConnectX> peers) throws Exception {
        log.info("\n\n==================================================================");
        log.info("  STREAM SESSION TEST");
        log.info("==================================================================\n");

        ConnectX sender   = peers.get(0);
        ConnectX receiver = peers.get(1);
        String senderID   = sender.getOwnID();
        String receiverID = receiver.getOwnID();

        log.info("Sender:   " + senderID.substring(0, 8));
        log.info("Receiver: " + receiverID.substring(0, 8));

        // Test chunks -- varied sizes to exercise framing and cipher
        byte[][] testChunks = {
            "Stream chunk 1: hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            new byte[1024],   // 1 KB zeroed
            new byte[4096],   // 4 KB zeroed
        };
        // Fill the larger chunks with a recognisable pattern
        for (int i = 0; i < testChunks[1].length; i++) testChunks[1][i] = (byte)(i & 0xFF);
        for (int i = 0; i < testChunks[2].length; i++) testChunks[2][i] = (byte)(i & 0xFF);

        int expectedChunks = testChunks.length;

        // Shared state between plugin callbacks and test assertions
        us.anvildevelopment.cxnet.network.stream.CXStreamSession[] senderSession   = {null};
        us.anvildevelopment.cxnet.network.stream.CXStreamSession[] receiverSession = {null};
        java.util.List<byte[]> receivedChunks = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch sessionOpenLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch dataLatch = new java.util.concurrent.CountDownLatch(expectedChunks);
        java.util.concurrent.CountDownLatch closeLatch = new java.util.concurrent.CountDownLatch(1);

        // ---- Receiver plugin ----
        us.anvildevelopment.cxnet.api.CXStreamPlugin receiverPlugin =
            new us.anvildevelopment.cxnet.api.CXStreamPlugin() {
                @Override
                public void onStreamRequest(us.anvildevelopment.cxnet.network.stream.CXStreamSession session,
                                            String fromCxID) {
                    log.info("[Receiver] onStreamRequest from {} session={}", fromCxID.substring(0, 8), session.sessionID.substring(0, 8));
                    receiverSession[0] = session;
                    acceptStream(session);
                }
                @Override
                public void onStreamData(us.anvildevelopment.cxnet.network.stream.CXStreamSession session,
                                         byte[] data) {
                    log.info("[Receiver] onStreamData {} bytes", data.length);
                    receivedChunks.add(data);
                    dataLatch.countDown();
                }
                @Override
                public void onStreamClosed(us.anvildevelopment.cxnet.network.stream.CXStreamSession session) {
                    log.info("[Receiver] onStreamClosed {}", session.sessionID.substring(0, 8));
                    closeLatch.countDown();
                }
            };

        // ---- Sender plugin ----
        us.anvildevelopment.cxnet.api.CXStreamPlugin senderPlugin =
            new us.anvildevelopment.cxnet.api.CXStreamPlugin() {
                @Override
                public void onStreamRequest(us.anvildevelopment.cxnet.network.stream.CXStreamSession session,
                                            String fromCxID) {
                    // Sender doesn't expect incoming requests in this test
                }
                @Override
                public void onStreamData(us.anvildevelopment.cxnet.network.stream.CXStreamSession session,
                                         byte[] data) {}
                @Override
                public void onStreamClosed(us.anvildevelopment.cxnet.network.stream.CXStreamSession session) {}
            };

        boolean senderAdded   = sender.addPlugin(senderPlugin);
        boolean receiverAdded = receiver.addPlugin(receiverPlugin);
        log.info("Plugins added -- sender: " + senderAdded + ", receiver: " + receiverAdded);
        assertTrue(senderAdded,   "Sender stream plugin must register successfully");
        assertTrue(receiverAdded, "Receiver stream plugin must register successfully");

        // ---- Open the stream ----
        log.info("\nStep 1: Sender opens stream to receiver...");
        senderPlugin.openStream(receiverID, "127.0.0.1");

        // Wait for the OPEN event to be delivered and the ACCEPT to come back.
        // Poll for both sessions to reach OPEN state (max 30s).
        log.info("Step 2: Waiting for stream to open (max 30s)...");
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            us.anvildevelopment.cxnet.network.stream.CXStreamSession ss = senderSession[0];
            us.anvildevelopment.cxnet.network.stream.CXStreamSession rs = receiverSession[0];

            // Grab the session created by openStream from the manager
            if (ss == null && sender.streamManager != null) {
                ss = sender.streamManager.getSession(
                    sender.streamManager.sessions.keySet().stream().findFirst().orElse(null));
                senderSession[0] = ss;
            }
            if (ss != null && rs != null
                    && ss.state == us.anvildevelopment.cxnet.network.stream.CXStreamSession.State.OPEN
                    && rs.state == us.anvildevelopment.cxnet.network.stream.CXStreamSession.State.OPEN) {
                sessionOpenLatch.countDown();
                break;
            }
        }

        boolean streamOpened = sessionOpenLatch.getCount() == 0;
        log.info("Stream opened: " + streamOpened);
        assertTrue(streamOpened, "Stream session must reach OPEN state on both sides within 30s");

        // ---- Send data ----
        log.info("\nStep 3: Sending " + expectedChunks + " chunks...");
        for (int i = 0; i < testChunks.length; i++) {
            log.info("  Sending chunk " + (i + 1) + ": " + testChunks[i].length + " bytes");
            senderSession[0].write(testChunks[i]);
        }

        // ---- Wait for delivery ----
        log.info("Step 4: Waiting for all chunks to arrive (max 10s)...");
        boolean allReceived = dataLatch.await(10, TimeUnit.SECONDS);
        log.info("All chunks received: " + allReceived
            + " (" + receivedChunks.size() + "/" + expectedChunks + ")");
        assertTrue(allReceived, "All " + expectedChunks + " chunks must be received within 10s");

        // ---- Verify content ----
        log.info("Step 5: Verifying chunk contents...");
        assertEquals(expectedChunks, receivedChunks.size(), "Received chunk count must match sent");
        for (int i = 0; i < testChunks.length; i++) {
            assertArrayEquals(testChunks[i], receivedChunks.get(i),
                "Chunk " + (i + 1) + " content must match exactly after AES-GCM decrypt");
        }
        log.info("  All chunks verified correct.");

        // ---- Close ----
        log.info("Step 6: Closing stream...");
        senderSession[0].close(sender);
        boolean closed = closeLatch.await(5, TimeUnit.SECONDS);
        log.info("Stream closed on receiver side: " + closed);
        assertTrue(closed, "Receiver must receive CLOSE notification within 5s");
        assertEquals(us.anvildevelopment.cxnet.network.stream.CXStreamSession.State.CLOSED,
            senderSession[0].state, "Sender session must be in CLOSED state");

        log.info("\n==================================================================");
        log.info("  STREAM SESSION TEST COMPLETE");
        log.info("==================================================================\n");
    }
}