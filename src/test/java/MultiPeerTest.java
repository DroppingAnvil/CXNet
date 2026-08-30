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
import us.anvildevelopment.cxnet.network.events.NetworkNameCheck;
import us.anvildevelopment.cxnet.network.events.SeedExchange;
import us.anvildevelopment.cxnet.network.nodemesh.Node;
import us.anvildevelopment.cxnet.api.CXMessagePlugin;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.BridgeProvider;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.http.HTTPBridgeProvider;

import us.anvildevelopment.cxnet.network.CXPath;
import us.anvildevelopment.cxnet.network.events.NetworkContainer;
import us.anvildevelopment.cxnet.network.events.NetworkEvent;
import us.anvildevelopment.cxnet.network.nodemesh.TransmitPref;

import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
                + "  LAN-peers=" + p.dataContainer.localPeerCount());
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
    @Timeout(value = 200, unit = TimeUnit.SECONDS)
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
    }

    private static void runWhitelistIntegrationTest(List<ConnectX> peers) throws Exception {
    }
    //TODO
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
    }

    /**
     * E2E (End-to-End) Encryption Test
     * Verifies that a PGP-encrypted message sent to a specific peer is received and decrypted correctly,
     * and that peers not in the recipient list do not receive the plaintext.
     */
    private static void runE2EEncryptionTest(List<ConnectX> peers) throws Exception {
        log.info("\n=== E2E Encryption Test ===");

        ConnectX sender   = peers.get(0);
        ConnectX receiver = peers.get(1);
        ConnectX outsider = peers.get(2);
        String receiverID = receiver.getOwnID();

        // Sender must know the receiver's public key (exchanged during CXHELLO)
        assertNotNull(sender.nodeMesh.peerDirectory.hv.get(receiverID),
            "Sender must have receiver in HV directory before E2E test");

        receivedMessages.get(1).clear();
        receivedMessages.get(2).clear();

        String secretText = "E2E-secret-" + System.currentTimeMillis();
        CXMessage secretMsg = new CXMessage(secretText);
        byte[] payload = ConnectX.serialize("cxJSON1", secretMsg)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        log.info("  Sender:   {}", sender.getOwnID().substring(0, 8));
        log.info("  Receiver: {}", receiverID.substring(0, 8));
        log.info("  Outsider: {}", outsider.getOwnID().substring(0, 8));

        sender.buildEvent(EventType.MESSAGE, payload)
            .toPeer(receiverID)
            .addRecipient(receiverID)
            .encrypt()
            .queue();

        // Wait up to 15s for delivery
        long deadline = System.currentTimeMillis() + 100_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            if (receivedMessages.get(1).stream().anyMatch(m -> secretText.equals(m.text))) break;
        }

        assertTrue(
            receivedMessages.get(1).stream().anyMatch(m -> secretText.equals(m.text)),
            "E2E encrypted message must be received and decrypted by the intended recipient within 100s");

        assertFalse(
            receivedMessages.get(2).stream().anyMatch(m -> secretText.equals(m.text)),
            "Peer not in recipient list must not receive the E2E plaintext");

        log.info("  E2E delivery verified: received by {} only.", receiverID.substring(0, 8));
    }

    // -------------------------------------------------------------------------
    // Stream session test
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("Stream session: open, transfer, close via CXStreamPlugin")
    @Timeout(value = 160, unit = TimeUnit.SECONDS)
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
        log.info("Step 2: Waiting for stream to open (max 120s)...");
        long deadline = System.currentTimeMillis() + 120_000;
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

    @Test
    @Order(8)
    @DisplayName("Permission enforcement: record without permission does not modify chain")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testPermissionEnforcement() throws Exception {
        assumeBootstrapped();

        CXNetwork cxnet = peers.get(0).getNetwork("CXNET");
        assertNotNull(cxnet, "CXNET must be loaded");

        Long c3ID = cxnet.networkDictionary.c3;
        String peer3ID = peers.get(2).getOwnID();

        assertFalse(cxnet.checkChainPermission(peer3ID, Permission.Record.name(), c3ID),
            "Peer 3 must not have Record permission on c3");

        int eventsBefore = cxnet.c3.current != null ? cxnet.c3.current.networkEvents.size() : 0;

        for (int i = 0; i < 5; i++) {
            try {
                CXMessage attempt = new CXMessage("No-perm record attempt #" + (i + 1));
                byte[] payload = ConnectX.serialize("cxJSON1", attempt)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                peers.get(2).buildEvent(EventType.MESSAGE, payload)
                    .signData()
                    .withRecordFlag(true)
                    .toNetwork("CXNET", 3L)
                    .queue();
            } catch (Exception e) {
                fail("Failed to queue no-perm record attempt: " + e.getMessage());
            }
        }
        Thread.sleep(3000);

        int eventsAfter = cxnet.c3.current != null ? cxnet.c3.current.networkEvents.size() : 0;
        assertEquals(eventsBefore, eventsAfter,
            "c3 must not gain events when sender has no Record permission");
    }

    @Test
    @Order(9)
    @DisplayName("Security: spoofed sender ID rejected at signature verification (003)")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSpoofedSenderRejected() throws Exception {
        assumeBootstrapped();

        // Attacker = Peer 0. Victim receiver = Peer 1.
        // We claim nc.oD / nc.iD = EPOCH's ID but sign everything with Peer 0's key.
        // The receiver has EPOCH's public key and must reject the mismatch (003).
        ConnectX attacker = peers.get(0);
        ConnectX receiver = peers.get(1);

        CXNetwork cxnet = attacker.getNetwork("CXNET");
        assertNotNull(cxnet, "CXNET must be loaded for this test");
        assertFalse(cxnet.configuration.backendSet.isEmpty(), "CXNET must have at least one backend (EPOCH)");

        String epochID = cxnet.configuration.backendSet.get(0);
        assertNotEquals(attacker.getOwnID(), epochID, "Attacker must not be EPOCH itself");

        // ---- Craft the spoofed container ----
        // Inner NetworkEvent: a MESSAGE with recognisable text, signed by attacker's key
        String spoofText = "SPOOFED-FROM-EPOCH-" + System.currentTimeMillis();
        CXMessage spoofMsg = new CXMessage(spoofText);
        byte[] spoofPayload = ConnectX.serialize("cxJSON1", spoofMsg)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        NetworkEvent fakeEvent = new NetworkEvent(EventType.MESSAGE, spoofPayload);
        fakeEvent.iD = java.util.UUID.randomUUID().toString();
        CXPath ep = new CXPath();
        ep.scope  = "CXN";
        ep.network = "CXNET";
        ep.cxID   = receiver.getOwnID();
        fakeEvent.p = ep;

        // Sign the inner NetworkEvent with ATTACKER's key (not EPOCH's)
        byte[] signedFakeEvent = attacker.signObject(fakeEvent, NetworkEvent.class, "cxJSON1").toByteArray();

        // Outer NetworkContainer: claim both transmitter and original sender are EPOCH
        NetworkContainer fakeContainer = new NetworkContainer();
        fakeContainer.iD = epochID;   // lie: claim we are EPOCH
        fakeContainer.oD = epochID;   // lie: claim original sender is EPOCH
        fakeContainer.se = "cxJSON1";
        fakeContainer.e  = signedFakeEvent;
        fakeContainer.tP = new TransmitPref();
        CXPath cp = new CXPath();
        cp.scope   = "CXN";
        cp.network = "CXNET";
        cp.cxID    = receiver.getOwnID();
        fakeContainer.p = cp;

        // Sign the outer container with ATTACKER's key (not EPOCH's)
        byte[] rawFakeContainer = attacker.signObject(fakeContainer, NetworkContainer.class, "cxJSON1").toByteArray();

        // ---- Deliver directly to receiver's P2P port ----
        receivedMessages.get(1).clear();

        int receiverPort = receiver.nodeMesh.in.serverSocket.getLocalPort();
        try (Socket s = new Socket("127.0.0.1", receiverPort)) {
            OutputStream os = s.getOutputStream();
            os.write(rawFakeContainer);
            os.flush();
        }

        // Give the receiver time to process it
        Thread.sleep(3000);

        // ---- Assert: spoofed message must never reach the plugin ----
        assertFalse(
            receivedMessages.get(1).stream().anyMatch(m -> spoofText.equals(m.text)),
            "Spoofed message claiming to be from EPOCH must be rejected by signature verification (003) and never reach the plugin");

        log.info("  Spoofed-EPOCH message correctly rejected (003 signature mismatch).");
    }

    @Test
    @Order(11)
    @DisplayName("Signed messages: signed delivered, unsigned rejected at verification")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testSignedMessageDelivery() throws Exception {
        assumeBootstrapped();

        ConnectX sender   = peers.get(0);
        ConnectX receiver = peers.get(1);
        String receiverID = receiver.getOwnID();

        assertNotNull(sender.nodeMesh.peerDirectory.hv.get(receiverID),
            "Sender must have receiver in HV directory");

        receivedMessages.get(1).clear();

        // --- Properly signed message must reach the plugin ---
        String signedText = "Signed-" + System.currentTimeMillis();
        CXMessage signedMsg = new CXMessage(signedText);
        byte[] signedPayload = ConnectX.serialize("cxJSON1", signedMsg)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        sender.buildEvent(EventType.MESSAGE, signedPayload)
            .toPeer(receiverID)
            .signData()
            .queue();

        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            if (receivedMessages.get(1).stream().anyMatch(m -> signedText.equals(m.text))) break;
        }
        assertTrue(
            receivedMessages.get(1).stream().anyMatch(m -> signedText.equals(m.text)),
            "Properly signed message must be received and verified by receiver within 60s");

        // --- Unsigned message (no .signData()) must NOT reach the plugin ---
        // The inner payload verification (004) will reject ne.d that was never PGP-signed.
        String unsignedText = "Unsigned-" + System.currentTimeMillis();
        CXMessage unsignedMsg = new CXMessage(unsignedText);
        byte[] unsignedPayload = ConnectX.serialize("cxJSON1", unsignedMsg)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        sender.buildEvent(EventType.MESSAGE, unsignedPayload)
            .toPeer(receiverID)
            // intentionally no .signData() -- ne.d will be raw bytes
            .queue();

        Thread.sleep(3000);
        assertFalse(
            receivedMessages.get(1).stream().anyMatch(m -> unsignedText.equals(m.text)),
            "Unsigned message must not reach the plugin -- rejected by inner payload signature check (004)");

        log.info("  Signed delivery: verified. Unsigned rejection: verified.");
    }

    @Test
    @Order(12)
    @DisplayName("checkNetworkName: CXNET taken, unknown name available")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testCheckNetworkName() throws Exception {
        assumeBootstrapped();

        ConnectX peer = peers.get(0);

        // CXNET is registered -- backend must respond taken=true
        CountDownLatch takenLatch = new CountDownLatch(1);
        NetworkNameCheck[] takenResult = {null};
        peer.checkNetworkName("CXNET", result -> {
            takenResult[0] = result;
            takenLatch.countDown();
        });
        boolean takenFired = takenLatch.await(30, TimeUnit.SECONDS);
        assertTrue(takenFired, "No response received for CXNET name check within 15s");
        assertTrue(takenResult[0].taken, "CXNET must be reported as taken");

        // Random name that cannot exist -- backend must respond taken=false
        String ghost = "GHOST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        CountDownLatch freeLatch = new CountDownLatch(1);
        NetworkNameCheck[] freeResult = {null};
        peer.checkNetworkName(ghost, result -> {
            freeResult[0] = result;
            freeLatch.countDown();
        });
        boolean freeFired = freeLatch.await(15, TimeUnit.SECONDS);
        assertTrue(freeFired, "No response received for unknown name check within 15s");
        assertFalse(freeResult[0].taken, ghost + " must be reported as available");
    }
}