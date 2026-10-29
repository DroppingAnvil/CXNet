import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import us.anvildevelopment.cxnet.network.nodemesh.EndpointVerifier;
import us.anvildevelopment.cxnet.network.nodemesh.Node;
import us.anvildevelopment.cxnet.network.nodemesh.PeerDirectory;
import us.anvildevelopment.cxnet.network.nodemesh.PeerEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Endpoint verification, concentrating on the negative cases.
 *
 * Acceptance is an identity check, not a reachability check, so most of the value is in proving
 * what must NOT verify -- above all a perfectly valid proof signed by a different real identity
 * that happens to hold the advertised address.
 */
public class EndpointVerifierTest {

    private static final String PEER = "peer-0000000000000000000000001";
    private static final String OTHER = "peer-0000000000000000000000002";
    private static final String ADDR = "198.51.100.7:7000";

    private static PeerDirectory directoryWith(String cxID) {
        PeerDirectory pd = new PeerDirectory(null);
        Node n = new Node();
        n.cxID = cxID;
        n.publicKey = "key-" + cxID;
        pd.addNode(n);
        return pd;
    }

    private static EndpointVerifier verifier(PeerDirectory pd) {
        return new EndpointVerifier(pd, 1);
    }

    @Test
    @DisplayName("A correct proof from the probed identity verifies the endpoint")
    void happyPath() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        String nonce = v.issue(PEER, ADDR);
        assertNotNull(nonce);

        assertTrue(v.resolve(nonce, PEER, PEER));

        PeerEntry pe = pd.entry(PEER);
        assertTrue(pe.endpointVerified);
        assertEquals(ADDR, pe.verifiedEndpoint);
        assertEquals(5.0f, pe.priscore(), 0.0001f, "3 availability + 2 verified endpoint");
        v.shutdown();
    }

    @Test
    @DisplayName("A valid proof signed by a DIFFERENT real identity must not verify")
    void validProofFromWrongIdentityRejected() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        String nonce = v.issue(PEER, ADDR);

        // OTHER holds the advertised address and answers with a signature that verifies fine
        // against its own key. Signature validity alone would accept this.
        assertFalse(v.resolve(nonce, OTHER, OTHER),
                "the signer must be the identity that was probed");

        PeerEntry pe = pd.entry(PEER);
        assertFalse(pe.endpointVerified, "no bonus for an endpoint held by someone else");
        assertEquals(0, pe.retries, "and node health must be untouched");
        v.shutdown();
    }

    @Test
    @DisplayName("A proof claiming one identity but signed by another must not verify")
    void claimAndSignerMismatchRejected() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        String nonce = v.issue(PEER, ADDR);
        assertFalse(v.resolve(nonce, PEER, OTHER),
                "claims to be the peer, signed by someone else");
        assertFalse(pd.entry(PEER).endpointVerified);
        v.shutdown();
    }

    @Test
    @DisplayName("An unsigned or unverifiable proof must not verify")
    void noSignerRejected() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        String nonce = v.issue(PEER, ADDR);
        // actualSigner == null models "reply arrived but nothing verified it" -- a bare TCP
        // accept or an HTTP 200 with no proof.
        assertFalse(v.resolve(nonce, PEER, null));
        assertFalse(pd.entry(PEER).endpointVerified);
        assertEquals(0, pd.entry(PEER).retries);
        v.shutdown();
    }

    @Test
    @DisplayName("A wrong or unknown nonce must not verify")
    void wrongNonceRejected() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        v.issue(PEER, ADDR);
        assertFalse(v.resolve("not-the-nonce-we-sent", PEER, PEER));
        assertFalse(pd.entry(PEER).endpointVerified);
        v.shutdown();
    }

    @Test
    @DisplayName("A nonce is single use, so a captured proof cannot be replayed")
    void nonceIsSingleUse() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        String nonce = v.issue(PEER, ADDR);
        assertTrue(v.resolve(nonce, PEER, PEER));

        // Clear the result and replay the exact same proof.
        pd.entry(PEER).recordEndpointFailure();
        assertFalse(v.resolve(nonce, PEER, PEER), "a replayed proof must be refused");
        assertFalse(pd.entry(PEER).endpointVerified);
        v.shutdown();
    }

    @Test
    @DisplayName("A failed probe drops the bonus but never increments retries")
    void failureLeavesNodeHealthAlone() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        PeerEntry pe = pd.entry(PEER);
        pe.recordEndpointVerified(ADDR);
        assertEquals(5.0f, pe.priscore(), 0.0001f);

        v.fail(PEER);

        assertFalse(pe.endpointVerified);
        assertEquals(0, pe.retries,
                "a dead bridge must not make a peer reachable by temp socket look dead");
        assertEquals(3.0f, pe.priscore(), 0.0001f);
        v.shutdown();
    }

    @Test
    @DisplayName("An endpoint failing in real use drops the bonus the same way")
    void failureInUse() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        PeerEntry pe = pd.entry(PEER);
        pe.recordEndpointVerified(ADDR);

        v.endpointFailedInUse(PEER, ADDR);
        assertFalse(pe.endpointVerified);
        assertEquals(0, pe.retries);
        v.shutdown();
    }

    @Test
    @DisplayName("A verified endpoint is not re-probed until the interval elapses")
    void reverifyIsLazy() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        PeerEntry pe = pd.entry(PEER);
        pe.recordEndpointVerified(ADDR);

        assertFalse(v.shouldVerify(PEER, ADDR), "just verified, nothing to do");
        assertNull(v.issue(PEER, ADDR));

        // Pretend the last check was long ago.
        pe.endpointCheckedAt = System.currentTimeMillis() - EndpointVerifier.REVERIFY_INTERVAL_MS - 1;
        assertTrue(v.shouldVerify(PEER, ADDR));
        v.shutdown();
    }

    @Test
    @DisplayName("Re-advertising a different address invalidates the existing proof immediately")
    void readvertisementForcesReverify() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        pd.entry(PEER).recordEndpointVerified(ADDR);
        assertFalse(v.shouldVerify(PEER, ADDR));
        assertTrue(v.shouldVerify(PEER, "203.0.113.9:7000"),
                "a different advertised address has not been proven");
        v.shutdown();
    }

    @Test
    @DisplayName("Only one probe per peer is in flight at a time")
    void oneProbeInFlight() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        assertNotNull(v.issue(PEER, ADDR));
        assertNull(v.issue(PEER, ADDR), "a second challenge must not be issued concurrently");

        v.fail(PEER);
        assertNotNull(v.issue(PEER, ADDR), "released once the first resolves");
        v.shutdown();
    }

    @Test
    @DisplayName("Unknown peers and empty addresses are not probed")
    void nothingToProbe() {
        PeerDirectory pd = directoryWith(PEER);
        EndpointVerifier v = verifier(pd);

        assertFalse(v.shouldVerify("nobody", ADDR));
        assertFalse(v.shouldVerify(PEER, null));
        assertFalse(v.shouldVerify(PEER, ""));
        assertFalse(v.shouldVerify(null, ADDR));
        v.shutdown();
    }

    @Test
    @DisplayName("Nonces are unique")
    void noncesAreUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertTrue(seen.add(EndpointVerifier.newNonce()), "nonce collision");
        }
    }
}
