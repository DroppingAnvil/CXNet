import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import us.anvildevelopment.cxnet.network.nodemesh.Node;
import us.anvildevelopment.cxnet.network.nodemesh.PeerDirectory;
import us.anvildevelopment.cxnet.network.nodemesh.PeerEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Priscore arithmetic, eviction order and the deprecated map projections.
 *
 * No ConnectX instance and no network: PeerDirectory's store is independent of both, which is
 * most of the point of collapsing it.
 */
public class PeerDirectoryTest {

    private static final float EPS = 0.0001f;

    private static PeerDirectory directory() {
        return new PeerDirectory(null);
    }

    private static Node node(String cxID) {
        Node n = new Node();
        n.cxID = cxID;
        n.publicKey = "key-" + cxID;
        return n;
    }

    /** cxIDs must be <= 36 chars to pass Node.validate. */
    private static String id(int i) {
        return String.format("peer-%031d", i);
    }

    // -----------------------------------------------------------------------------------
    // Priscore
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Availability term: fresh peer scores 3, then 1.5, 1, 0.75, never zero")
    void availabilityTerm() {
        PeerEntry pe = new PeerEntry();
        assertEquals(3.0f, pe.priscore(), EPS, "fresh peer");

        pe.retries = 1;
        assertEquals(1.5f, pe.priscore(), EPS, "one retry -- float division, not integer");

        pe.retries = 2;
        assertEquals(1.0f, pe.priscore(), EPS);

        pe.retries = 3;
        assertEquals(0.75f, pe.priscore(), EPS);

        pe.retries = 1_000_000;
        assertTrue(pe.priscore() > 0f, "availability term must never reach zero");
    }

    @Test
    @DisplayName("Endpoint term: verified endpoint is +2")
    void verifiedEndpointTier() {
        PeerEntry pe = new PeerEntry();
        pe.endpointVerified = true;
        assertEquals(5.0f, pe.priscore(), EPS, "3 availability + 2 endpoint");
    }

    @Test
    @DisplayName("Endpoint term: local subnet and user-linked are +3, outranking a verified endpoint")
    void localAndLinkedTier() {
        PeerEntry local = new PeerEntry();
        local.localSubnet = true;
        assertEquals(6.0f, local.priscore(), EPS, "3 availability + 3 local");

        PeerEntry linked = new PeerEntry();
        linked.userLinked = true;
        assertEquals(6.0f, linked.priscore(), EPS, "3 availability + 3 linked");

        PeerEntry remote = new PeerEntry();
        remote.endpointVerified = true;
        assertTrue(local.priscore() > remote.priscore(),
                "a fresh local peer must outrank a fresh remote peer with a verified endpoint");
    }

    @Test
    @DisplayName("Endpoint term is tiered, not additive: local plus verified is +3, not +5")
    void tiersDoNotStack() {
        PeerEntry both = new PeerEntry();
        both.localSubnet = true;
        both.endpointVerified = true;
        assertEquals(6.0f, both.priscore(), EPS, "max of the two tiers, not their sum");
        assertTrue(both.priscore() <= 6.0f, "priscore must stay within 0..6");
    }

    @Test
    @DisplayName("recordSuccess clears retries so a recovered peer is not left buried")
    void successResetsRetries() {
        PeerEntry pe = new PeerEntry();
        pe.retries = 8;
        assertTrue(pe.priscore() < 1f);

        pe.recordSuccess();
        assertEquals(0, pe.retries);
        assertEquals(3.0f, pe.priscore(), EPS);
        assertTrue(pe.lastContact > 0);
    }

    @Test
    @DisplayName("Endpoint failure drops the bonus without touching node health")
    void endpointHealthIsSeparateFromNodeHealth() {
        PeerEntry pe = new PeerEntry();
        pe.recordEndpointVerified("1.2.3.4:9000");
        assertEquals(5.0f, pe.priscore(), EPS);

        pe.recordEndpointFailure();

        assertEquals(0, pe.retries, "a bad endpoint must not make a reachable peer look dead");
        assertFalse(pe.endpointVerified);
        assertNull(pe.verifiedEndpoint);
        assertEquals(3.0f, pe.priscore(), EPS, "lost the +2, kept full availability");
    }

    // -----------------------------------------------------------------------------------
    // Eviction
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Store holds at the 100 cap, evicting the lowest priscore first")
    void capAndEvictionOrder() {
        PeerDirectory pd = directory();

        // 100 healthy peers, then one more that should push out a low scorer.
        for (int i = 0; i < PeerDirectory.MAX_PEERS; i++) {
            pd.addNode(node(id(i)));
        }
        assertEquals(PeerDirectory.MAX_PEERS, pd.peers.size());

        // Bury one peer so it is the clear eviction candidate.
        PeerEntry doomed = pd.entry(id(7));
        doomed.retries = 50;

        pd.addNode(node(id(1000)));

        assertEquals(PeerDirectory.MAX_PEERS, pd.peers.size(), "cap must hold after insert");
        assertNull(pd.entry(id(7)), "lowest priscore should have been evicted");
        assertNotNull(pd.entry(id(1000)), "the new peer should be present");
    }

    @Test
    @DisplayName("User-linked and backend-pinned peers survive eviction regardless of score")
    void exemptionsSurviveEviction() {
        PeerDirectory pd = directory();

        pd.addNode(node(id(1)));
        pd.linkPeer(id(1));
        PeerEntry linked = pd.entry(id(1));
        linked.retries = 9999; // worst possible availability

        pd.addNode(node(id(2)));
        PeerEntry pinned = pd.entry(id(2));
        pinned.backendPinned = true;
        pinned.retries = 9999;

        // Flood well past the cap.
        for (int i = 10; i < 10 + PeerDirectory.MAX_PEERS * 2; i++) {
            pd.addNode(node(id(i)));
        }

        assertNotNull(pd.entry(id(1)), "user-linked peer must never be evicted");
        assertTrue(pd.entry(id(1)).userLinked);
        assertNotNull(pd.entry(id(2)), "backend-pinned peer must never be evicted");
    }

    @Test
    @DisplayName("Exempt entries do not block eviction of evictable ones")
    void exemptionsDoNotStallEviction() {
        PeerDirectory pd = directory();
        for (int i = 0; i < 20; i++) {
            pd.addNode(node(id(i)));
            pd.linkPeer(id(i));
        }
        for (int i = 100; i < 100 + PeerDirectory.MAX_PEERS; i++) {
            pd.addNode(node(id(i)));
        }
        // All 20 linked peers survive; the rest are trimmed toward the cap.
        for (int i = 0; i < 20; i++) {
            assertNotNull(pd.entry(id(i)), "linked peer " + i + " evicted");
        }
    }

    @Test
    @DisplayName("ranked() orders by priscore descending")
    void rankedOrder() {
        PeerDirectory pd = directory();
        pd.addNode(node(id(1)));
        pd.addNode(node(id(2)));
        pd.addNode(node(id(3)));

        pd.entry(id(1)).retries = 5;                  // low
        pd.entry(id(2)).endpointVerified = true;      // 5.0
        pd.entry(id(3)).localSubnet = true;           // 6.0

        List<PeerEntry> ranked = pd.ranked();
        assertEquals(3, ranked.size());
        assertEquals(id(3), ranked.get(0).node.cxID, "local peer first");
        assertEquals(id(2), ranked.get(1).node.cxID, "verified endpoint second");
        assertEquals(id(1), ranked.get(2).node.cxID, "retry-buried peer last");
    }

    @Test
    @DisplayName("fanoutTargets returns top K plus the requested random tail")
    void fanoutTopKPlusRandom() {
        PeerDirectory pd = directory();
        for (int i = 0; i < 40; i++) pd.addNode(node(id(i)));

        List<PeerEntry> targets = pd.fanoutTargets(10, 3);
        assertEquals(13, targets.size());

        // With fewer peers than K, everything is returned and nothing duplicated.
        PeerDirectory small = directory();
        small.addNode(node(id(1)));
        small.addNode(node(id(2)));
        assertEquals(2, small.fanoutTargets(10, 3).size());
    }

    // -----------------------------------------------------------------------------------
    // Addresses
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Priority addresses prepend; observed sockets replace rather than accumulate")
    void addressPriorityAndSocketReplacement() {
        PeerDirectory pd = directory();
        String peer = id(1);

        pd.recordLocalPeer(peer, "10.0.0.5:5000", false);
        pd.recordLocalPeer(peer, "10.0.0.5:5001", false);
        pd.recordLocalPeer(peer, "10.0.0.5:5002", false);

        List<String> addresses = pd.getLocalPeerAddresses(peer);
        assertEquals(1, addresses.size(),
                "each observed socket replaces the last, or a busy peer collects dead ports");
        assertEquals("10.0.0.5:5002", addresses.get(0));

        pd.recordLocalPeer(peer, "10.0.0.5:9000", true);
        assertEquals("10.0.0.5:9000", pd.getLocalPeerAddress(peer),
                "advertised address takes priority position");
        assertEquals(2, pd.getLocalPeerAddresses(peer).size());
    }

    @Test
    @DisplayName("deprioritizeFailedRoute moves failed routes behind the one that worked")
    void deprioritizeFailedRoutes() {
        PeerDirectory pd = directory();
        String peer = id(1);
        pd.recordLocalPeer(peer, "a:1", true);
        pd.recordLocalPeer(peer, "b:2", true);
        pd.recordLocalPeer(peer, "c:3", true);
        // Priority prepends, so order is now c, b, a.
        assertEquals(List.of("c:3", "b:2", "a:1"), pd.getLocalPeerAddresses(peer));

        pd.deprioritizeFailedRoute(peer, "a:1", 2);
        assertEquals("a:1", pd.getLocalPeerAddresses(peer).get(0),
                "the working route should now be tried first");
    }

    @Test
    @DisplayName("getAllAddresses merges recorded addresses with the Node's advertised one")
    void getAllAddressesMerges() {
        PeerDirectory pd = directory();
        String peer = id(1);
        Node n = node(peer);
        n.addr = "198.51.100.7:7000";
        pd.addNode(n);
        pd.recordLocalPeer(peer, "192.168.1.9:5000", true);

        List<String> all = pd.getAllAddresses(peer, null);
        assertEquals(2, all.size());
        assertEquals("192.168.1.9:5000", all.get(0), "recorded addresses come first");
        assertTrue(all.contains("198.51.100.7:7000"));
    }

    @Test
    @DisplayName("A peer known only via hv still resolves its advertised address")
    void backboneOnlyPeerResolvesAddress() {
        PeerDirectory pd = directory();
        Node n = node(id(1));
        n.addr = "cxHTTP1:https://example.invalid/cx";

        // Registered straight into the backbone with no PeerEntry, which is what
        // BootstrapServerTest does for EPOCH.
        pd.hv.put(n.cxID, n);

        List<String> all = pd.getAllAddresses(id(1), null);
        assertFalse(all.isEmpty(),
                "every targeted send -- CXS, E2E, streams -- resolves routes through this method, "
                        + "so an hv-only peer resolving to zero addresses is unreachable");
        assertTrue(all.contains(n.addr));
    }

    @Test
    @DisplayName("An hv address is not duplicated when the same peer also has a directory entry")
    void backboneAddressNotDuplicated() {
        PeerDirectory pd = directory();
        Node n = node(id(1));
        n.addr = "198.51.100.7:7000";
        pd.addBackend(n);
        pd.recordLocalPeer(id(1), "198.51.100.7:7000", true);

        List<String> all = pd.getAllAddresses(id(1), null);
        assertEquals(1, all.size(), "the same address must not appear twice");
    }

    @Test
    @DisplayName("localPeerCount counts only peers with a known address")
    void localPeerCounting() {
        PeerDirectory pd = directory();
        pd.addNode(node(id(1)));
        assertEquals(0, pd.localPeerCount());
        pd.recordLocalPeer(id(1), "10.0.0.1:5000", true);
        assertEquals(1, pd.localPeerCount());
    }

    // -----------------------------------------------------------------------------------
    // Deprecated projections
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("seen projects every peer; lan and peerCache stay filtered")
    void projectionsReflectTheStore() {
        PeerDirectory pd = directory();
        pd.addNode(node(id(1)));
        pd.addNode(node(id(2)));

        assertEquals(2, pd.seen.size(), "seen projects the whole store");
        assertTrue(pd.seen.containsKey(id(1)));
        assertNotNull(pd.seen.get(id(1)));
        assertEquals(2, pd.seen.values().size());

        assertEquals(0, pd.lan.size(), "no peer is on a local subnet yet");
        assertFalse(pd.lan.containsKey(id(1)));

        pd.entry(id(1)).localSubnet = true;
        assertEquals(1, pd.lan.size(), "lan must stay filtered, not alias the whole store");
        assertTrue(pd.lan.containsKey(id(1)));
        assertFalse(pd.lan.containsKey(id(2)));

        assertEquals(0, pd.peerCache.size());
        pd.entry(id(2)).diskCached = true;
        assertEquals(1, pd.peerCache.size());
    }

    @Test
    @DisplayName("Writing through lan tags the peer as local, matching the old field's meaning")
    void lanPutTagsLocalSubnet() {
        PeerDirectory pd = directory();
        pd.lan.put(id(1), node(id(1)));

        assertTrue(pd.entry(id(1)).localSubnet, "lan.put must still mean 'on a local subnet'");
        assertEquals(6.0f, pd.priscore(id(1)), EPS, "and therefore carry the +3 tier");
    }

    @Test
    @DisplayName("Removing through a projection removes from the backing store")
    void projectionRemoveReachesStore() {
        PeerDirectory pd = directory();
        pd.addNode(node(id(1)));
        assertNotNull(pd.seen.remove(id(1)));
        assertNull(pd.entry(id(1)), "removal must reach the store, not just the projection");
    }

    // -----------------------------------------------------------------------------------
    // Identity handling
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Same cxID with a different public key is rejected as spoofing")
    void uuidSpoofingRejected() {
        PeerDirectory pd = directory();
        pd.addNode(node(id(1)));

        Node impostor = node(id(1));
        impostor.publicKey = "a-completely-different-key";

        assertThrows(SecurityException.class, () -> pd.addNode(impostor));
    }

    @Test
    @DisplayName("addBackend pins the peer and registers it in hv")
    void backendRegistrationPins() {
        PeerDirectory pd = directory();
        pd.addBackend(node(id(1)));

        assertTrue(pd.hv.containsKey(id(1)), "hv now holds the CXNET backbone");
        assertTrue(pd.entry(id(1)).backendPinned, "backends are eviction-exempt");
        assertTrue(pd.entry(id(1)).evictionExempt());
    }

    @Test
    @DisplayName("addNode no longer writes hv, so the backbone stays separate from the directory")
    void addNodeDoesNotPopulateBackbone() {
        PeerDirectory pd = directory();
        pd.addNode(node(id(1)));

        assertTrue(pd.hv.isEmpty(),
                "addNode used to write hv for every peer, making it an unbounded second copy");
        assertEquals(1, pd.peers.size());
    }

    @Test
    @DisplayName("linkPeer and unlinkPeer toggle the exemption")
    void linkAndUnlink() {
        PeerDirectory pd = directory();
        pd.linkPeer(id(1));
        assertTrue(pd.isUserLinked(id(1)));
        assertTrue(pd.entry(id(1)).evictionExempt());

        pd.unlinkPeer(id(1));
        assertFalse(pd.isUserLinked(id(1)));
        assertFalse(pd.entry(id(1)).evictionExempt(), "unlinked peers become ordinary and evictable");
    }
}
