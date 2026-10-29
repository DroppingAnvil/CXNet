/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proves that a peer's ADVERTISED endpoint really reaches that peer.
 *
 * An advertised tcp4 address or bridge is pinned and stable, which is why it earns a priscore
 * bonus, but only if it is real. Advertisement alone is a claim; the rest of the mesh runs on
 * temp sockets precisely because claims go stale.
 *
 * Acceptance is an identity check, not a reachability check. None of the following is evidence:
 * a completed TCP connect, an HTTP 200 from a bridge, a well-formed reply, or any reply at all.
 * The endpoint earns its +2 only when the proof echoes the exact nonce, the signature verifies,
 * and the signer is the cxID that was being probed. The last of those is the one that is easy to
 * leave out and the reason a signature check alone is insufficient: an advertised address may be
 * held by a different real CX node, whose proof verifies perfectly well against its own key.
 * Checking "a valid signature" accepts that impostor; checking "signed by the identity I probed"
 * rejects it.
 *
 * Runs on its own small pool. Dialing a socket from the EventProcessor is the failure mode
 * CXAppDispatcher was introduced in v0.6.2 to fix, and this does exactly that kind of blocking
 * work.
 */
public class EndpointVerifier {
    private static final Logger log = LoggerFactory.getLogger(EndpointVerifier.class);

    /** A nonce is only valid this long after issue. */
    public static final long NONCE_TTL_MS = 30_000L;

    /** Re-verify an endpoint at most this often while it keeps succeeding. */
    public static final long REVERIFY_INTERVAL_MS = 15 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** An outstanding challenge. Bound to one cxID and one address. */
    public static final class Pending {
        public final String target;
        public final String address;
        public final long issuedAt;

        Pending(String target, String address) {
            this.target = target;
            this.address = address;
            this.issuedAt = System.currentTimeMillis();
        }

        boolean expired() {
            return System.currentTimeMillis() - issuedAt > NONCE_TTL_MS;
        }
    }

    /** nonce -> the challenge it was issued for. Single use: resolved nonces are removed. */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    /** cxIDs with a probe in flight, so a busy peer is not challenged repeatedly. */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    private final PeerDirectory peerDirectory;
    private final ExecutorService pool;

    public EndpointVerifier(PeerDirectory peerDirectory, int threads) {
        this.peerDirectory = peerDirectory;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "CX-EndpointVerifier-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** Fresh single-use nonce. */
    public static String newNonce() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /**
     * Whether an endpoint is worth probing now: there is an address to test, no probe is already
     * in flight, and either it has never been verified or the re-verify interval has elapsed.
     */
    public boolean shouldVerify(String cxID, String address) {
        if (cxID == null || address == null || address.isEmpty()) return false;
        if (inFlight.containsKey(cxID)) return false;

        PeerEntry pe = peerDirectory != null ? peerDirectory.entry(cxID) : null;
        if (pe == null) return false;

        // A re-advertisement of a different address invalidates the old proof immediately.
        if (pe.endpointVerified && address.equals(pe.verifiedEndpoint)) {
            return System.currentTimeMillis() - pe.endpointCheckedAt > REVERIFY_INTERVAL_MS;
        }
        return true;
    }

    /**
     * Register a challenge and return its nonce, or null if one should not be issued.
     * The caller sends the challenge to the address itself; this only tracks the expectation.
     */
    public String issue(String cxID, String address) {
        if (!shouldVerify(cxID, address)) return null;
        sweep();
        inFlight.put(cxID, Boolean.TRUE);
        String nonce = newNonce();
        pending.put(nonce, new Pending(cxID, address));
        log.debug("[EndpointVerifier] Challenged {} at {}", shortID(cxID), address);
        return nonce;
    }

    /**
     * Resolve a proof.
     *
     * @param nonce      the nonce echoed by the proof
     * @param claimedBy  the cxID the proof says answered
     * @param actualSigner the cxID whose key actually verified the proof's signature, as
     *                     determined by the caller's signature check. Must be the identity that
     *                     was probed; a different valid identity answering the address is a
     *                     failure, not a success.
     * @return true if the endpoint is now verified
     */
    public boolean resolve(String nonce, String claimedBy, String actualSigner) {
        if (nonce == null) return false;

        // Single use: removed whether or not it turns out to be valid, so a captured proof
        // cannot be replayed and a wrong answer cannot be retried against the same nonce.
        Pending p = pending.remove(nonce);
        if (p == null) {
            log.debug("[EndpointVerifier] Unknown or already-used nonce");
            return false;
        }
        inFlight.remove(p.target);

        if (p.expired()) {
            log.debug("[EndpointVerifier] Nonce for {} expired", shortID(p.target));
            fail(p.target);
            return false;
        }

        // The signature must be from the identity that was probed. Not merely "a" valid
        // signature: an advertised address may be held by another real CX node.
        if (actualSigner == null || !actualSigner.equals(p.target)) {
            log.info("[EndpointVerifier] {} at {} answered by a different identity ({}) -- rejecting",
                    shortID(p.target), p.address, shortID(actualSigner));
            fail(p.target);
            return false;
        }

        // A proof that names someone other than its signer is inconsistent on its face.
        if (claimedBy != null && !claimedBy.equals(actualSigner)) {
            log.info("[EndpointVerifier] {} proof claims {} but is signed by {} -- rejecting",
                    shortID(p.target), shortID(claimedBy), shortID(actualSigner));
            fail(p.target);
            return false;
        }

        PeerEntry pe = peerDirectory != null ? peerDirectory.entry(p.target) : null;
        if (pe == null) return false;
        pe.recordEndpointVerified(p.address);
        log.info("[EndpointVerifier] {} verified at {}", shortID(p.target), p.address);
        return true;
    }

    /**
     * The probe produced no usable proof: timeout, wrong nonce, bad signature, or the right
     * signature from the wrong identity.
     *
     * Drops the endpoint bonus and leaves {@code retries} alone. Endpoint health is tracked
     * separately from node health, so a dead bridge must not make a peer that is still reachable
     * over a temp socket look dead.
     */
    public void fail(String cxID) {
        if (cxID == null) return;
        inFlight.remove(cxID);
        PeerEntry pe = peerDirectory != null ? peerDirectory.entry(cxID) : null;
        if (pe != null) pe.recordEndpointFailure();
    }

    /**
     * An endpoint failed while being used for real traffic. Same treatment as a failed probe:
     * drop the bonus, leave node health untouched.
     */
    public void endpointFailedInUse(String cxID, String address) {
        PeerEntry pe = peerDirectory != null ? peerDirectory.entry(cxID) : null;
        if (pe == null) return;
        if (address == null || address.equals(pe.verifiedEndpoint)) {
            pe.recordEndpointFailure();
        }
    }

    /** Run a probe off the mesh threads. */
    public void submit(Runnable probe) {
        try {
            pool.submit(probe);
        } catch (Exception e) {
            log.debug("[EndpointVerifier] Could not submit probe: {}", e.getMessage());
        }
    }

    /** Expire nonces nobody answered, releasing their in-flight guards. */
    public void sweep() {
        for (java.util.Map.Entry<String, Pending> e : pending.entrySet()) {
            if (e.getValue() != null && e.getValue().expired()) {
                pending.remove(e.getKey());
                fail(e.getValue().target);
            }
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private static String shortID(String cxID) {
        if (cxID == null) return "null";
        return cxID.length() >= 8 ? cxID.substring(0, 8) : cxID;
    }
}
