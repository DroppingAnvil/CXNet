/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.analytics.AnalyticData;
import us.anvildevelopment.cxnet.analytics.Analytics;
import us.anvildevelopment.cxnet.exceptions.UnsafeKeywordException;
import us.anvildevelopment.util.tools.database.annotations.MemoryOnly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PeerDirectory implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(PeerDirectory.class);

    /**
     * Maximum tracked peers. Entries flagged userLinked or backendPinned do not count against it
     * and are never evicted.
     */
    public static final int MAX_PEERS = 100;

    /**
     * The peer store. One PeerEntry per cxID, holding identity, addresses, retry state and
     * endpoint health together.
     *
     * Replaces the old seen/lan/peerCache trio, which overlapped (addNode wrote to two of them),
     * were unbounded, and carried no notion of which peers were actually reachable. seen, lan and
     * peerCache remain as deprecated projections over this map so the existing call sites keep
     * working while they are migrated.
     */
    public ConcurrentHashMap<String, PeerEntry> peers = new ConcurrentHashMap<>();

    /**
     * CXNET backend nodes.
     *
     * Formerly "high-value peers" and written to by addNode() for every peer from any source,
     * which made it an unbounded second copy of the directory. It now holds exactly the CXNET
     * backend identities, which is what the routing code actually wanted from it. BackendMap
     * references these by cxID.
     */
    public ConcurrentHashMap<String,Node> hv = new ConcurrentHashMap<>();

    /**
     * All tracked peers, as Nodes.
     * @deprecated projection over {@link #peers}; use the peers map or {@link #node(String)}.
     */
    @Deprecated
    public Map<String,Node> seen = new NodeMapView(this, NodeMapView.ALL);

    /**
     * Peers found on one of this node's interface subnets.
     * @deprecated projection over {@link #peers} filtered on PeerEntry.localSubnet.
     */
    @Deprecated
    public Map<String,Node> lan = new NodeMapView(this, NodeMapView.LOCAL_SUBNET);

    /**
     * Peers loaded from disk rather than met live.
     * @deprecated projection over {@link #peers} filtered on PeerEntry.diskCached.
     */
    @Deprecated
    public Map<String,Node> peerCache = new NodeMapView(this, NodeMapView.DISK_CACHED);

    /**
     * cxIDs seen as a transmitter at least once. Cheaper than holding a Node, and used where only
     * "have we ever heard from this id" matters.
     * Thread-safe: backed by ConcurrentHashMap for O(1) contains/add under concurrent IOThreads.
     */
    public Set<String> seenCXIDs = ConcurrentHashMap.newKeySet();

    /**
     * Directory holding persisted peer identities ({cxRoot}/nodemesh).
     * Renamed from "peers", which the peer store now uses.
     */
    public File peersDir;

    @MemoryOnly
    public ConnectX connectX = null;

    /** Guards cap enforcement so two concurrent inserts cannot both evict. */
    private final transient Object evictionLock = new Object();

    public PeerDirectory(ConnectX cx) {
        if (connectX == null) {
            connectX = cx;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Store access
    // ---------------------------------------------------------------------------------------

    /** The entry for a cxID, or null. */
    public PeerEntry entry(String cxID) {
        if (cxID == null) return null;
        return peers.get(cxID);
    }

    /** The entry for a cxID, creating an empty one if absent. Never null. */
    public PeerEntry entryOrCreate(String cxID) {
        if (cxID == null) throw new IllegalArgumentException("cxID required");
        PeerEntry created = peers.computeIfAbsent(cxID, k -> {
            PeerEntry pe = new PeerEntry();
            pe.lastSeen = System.currentTimeMillis();
            return pe;
        });
        enforceCap();
        return created;
    }

    /** The Node for a cxID from the in-memory store only, or null. */
    public Node node(String cxID) {
        PeerEntry pe = entry(cxID);
        return pe != null ? pe.node : null;
    }

    /**
     * Every known identity, backends included.
     *
     * This is what the code that used to iterate {@code hv} almost always meant: "peers I might
     * be able to reach". hv is now specifically the CXNET backbone, so general fanout, discovery
     * and relay read from here instead.
     */
    public java.util.Collection<Node> allNodes() {
        List<Node> out = new ArrayList<>(peers.size());
        for (PeerEntry pe : peers.values()) {
            if (pe != null && pe.node != null) out.add(pe.node);
        }
        return out;
    }

    /** Every known cxID, including entries with no imported identity yet. */
    public List<String> allCXIDs() {
        return new ArrayList<>(peers.keySet());
    }

    /** Priscore for a cxID, or 0 if unknown. */
    public float priscore(String cxID) {
        PeerEntry pe = entry(cxID);
        return pe != null ? pe.priscore() : 0f;
    }

    /** Mark a successful contact, clearing the retry count. */
    public void recordSuccess(String cxID) {
        PeerEntry pe = entry(cxID);
        if (pe != null) pe.recordSuccess();
    }

    /** Mark a failed contact attempt against the peer. */
    public void recordFailure(String cxID) {
        PeerEntry pe = entry(cxID);
        if (pe != null) pe.recordFailure();
    }

    /**
     * Peers ranked by priscore, highest first, with lastSeen descending as tiebreak.
     * Excludes this node and entries with no usable identity.
     */
    public List<PeerEntry> ranked() {
        List<PeerEntry> out = new ArrayList<>(peers.size());
        String self = connectX != null ? connectX.getOwnID() : null;
        for (Map.Entry<String, PeerEntry> e : peers.entrySet()) {
            if (self != null && self.equals(e.getKey())) continue;
            if (e.getValue() == null) continue;
            out.add(e.getValue());
        }
        out.sort(Comparator.comparingDouble((PeerEntry p) -> -p.priscore())
                           .thenComparingLong(p -> -p.lastSeen));
        return out;
    }

    /**
     * Fanout target selection: the top K peers by priscore plus up to {@code random} others drawn
     * from the remainder.
     *
     * The random tail matters. Pure top-K means a node's fanout set is static, so a peer that
     * never cracks the top K is never contacted and never earns a score that would get it there.
     * A few random picks keep the mesh from stratifying.
     */
    public List<PeerEntry> fanoutTargets(int k, int random) {
        List<PeerEntry> all = ranked();
        if (k <= 0 && random <= 0) return all;
        if (all.size() <= k) return all;

        List<PeerEntry> out = new ArrayList<>(all.subList(0, Math.max(0, k)));
        List<PeerEntry> rest = new ArrayList<>(all.subList(Math.max(0, k), all.size()));
        if (random > 0 && !rest.isEmpty()) {
            java.util.Collections.shuffle(rest);
            out.addAll(rest.subList(0, Math.min(random, rest.size())));
        }
        return out;
    }

    /**
     * Drop the lowest-scoring entries once over MAX_PEERS.
     *
     * Synchronized because the cap is a whole-map invariant: two concurrent inserts each seeing
     * size()==101 would otherwise both evict. Everything else in this class stays lock-free, and
     * at 100 entries a linear pass is cheaper than maintaining a sorted index.
     */
    public void enforceCap() {
        if (peers.size() <= MAX_PEERS) return;
        synchronized (evictionLock) {
            List<Map.Entry<String, PeerEntry>> candidates = new ArrayList<>();
            for (Map.Entry<String, PeerEntry> e : peers.entrySet()) {
                PeerEntry pe = e.getValue();
                if (pe == null || !pe.evictionExempt()) candidates.add(e);
            }

            int overBy = peers.size() - MAX_PEERS;
            if (overBy <= 0 || candidates.isEmpty()) return;

            // Lowest priscore first, oldest lastSeen as the tiebreak.
            candidates.sort(Comparator
                    .comparingDouble((Map.Entry<String, PeerEntry> e) ->
                            e.getValue() == null ? 0f : e.getValue().priscore())
                    .thenComparingLong(e -> e.getValue() == null ? 0L : e.getValue().lastSeen));

            int removed = 0;
            for (Map.Entry<String, PeerEntry> e : candidates) {
                if (removed >= overBy) break;
                peers.remove(e.getKey());
                removed++;
            }
            if (removed > 0) {
                log.debug("[PeerDirectory] Evicted {} peers to hold the {} cap ({} remain)",
                        removed, MAX_PEERS, peers.size());
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // User-linked peers
    // ---------------------------------------------------------------------------------------

    /**
     * Explicitly link a peer. Linked peers are exempt from eviction and carry the +3 endpoint
     * tier, so a remote churn storm cannot push a user's own devices out of the directory.
     *
     * This is the feature DataContainer.LAN declared but never implemented.
     */
    public void linkPeer(String cxID) {
        if (cxID == null) return;
        entryOrCreate(cxID).userLinked = true;
        log.info("[PeerDirectory] Linked peer {}", shortID(cxID));
    }

    /** Remove the user link. The entry becomes ordinary and evictable. */
    public void unlinkPeer(String cxID) {
        PeerEntry pe = entry(cxID);
        if (pe != null) {
            pe.userLinked = false;
            log.info("[PeerDirectory] Unlinked peer {}", shortID(cxID));
        }
    }

    public boolean isUserLinked(String cxID) {
        PeerEntry pe = entry(cxID);
        return pe != null && pe.userLinked;
    }

    // ---------------------------------------------------------------------------------------
    // Addresses (migrated from DataContainer)
    // ---------------------------------------------------------------------------------------

    /**
     * Record an address for a peer.
     *
     * @param priority true for an advertised address (CXHELLO payload): prepended and kept.
     *                 false for a passively observed receiving socket: appended, replacing the
     *                 previous observed socket rather than accumulating. Without that
     *                 replacement a busy peer collects dozens of dead ephemeral ports.
     */
    public void recordLocalPeer(String peerID, String address, boolean priority) {
        if (peerID == null || address == null || address.isEmpty()) return;
        PeerEntry pe = entryOrCreate(peerID);
        pe.lastSeen = System.currentTimeMillis();

        // Any address on one of this machine's interface subnets earns the +3 tier. Matched
        // against real interface addresses, not RFC1918 ranges, so a VPN peer or a CGNAT peer
        // does not pass as local. Local means cheap to reach; it is not trust, and the peer
        // still goes through the nonce challenge.
        if (!pe.localSubnet && LocalSubnets.isLocalAddress(address)) {
            pe.localSubnet = true;
        }

        synchronized (pe.addresses) {
            if (pe.addresses.contains(address)) return;
            if (priority) {
                pe.addresses.add(0, address);
            } else {
                if (pe.lastReceivingSocket != null) {
                    pe.addresses.remove(pe.lastReceivingSocket);
                }
                pe.addresses.add(address);
                pe.lastReceivingSocket = address;
            }
        }
    }

    public void recordLocalPeer(String peerID, String address) {
        recordLocalPeer(peerID, address, false);
    }

    /** First (highest priority) known address for a peer, or null. */
    public String getLocalPeerAddress(String peerID) {
        PeerEntry pe = entry(peerID);
        if (pe == null) return null;
        synchronized (pe.addresses) {
            return pe.addresses.isEmpty() ? null : pe.addresses.get(0);
        }
    }

    /** All known addresses for a peer in priority order. Never null. */
    public List<String> getLocalPeerAddresses(String peerID) {
        PeerEntry pe = entry(peerID);
        if (pe == null) return new ArrayList<>();
        synchronized (pe.addresses) {
            return new ArrayList<>(pe.addresses);
        }
    }

    public void removeLocalPeer(String peerID) {
        PeerEntry pe = entry(peerID);
        if (pe != null) {
            synchronized (pe.addresses) {
                pe.addresses.clear();
                pe.lastReceivingSocket = null;
            }
        }
    }

    /**
     * Move routes that failed ahead of a successful one to the end of the list, so the next send
     * tries the working route first.
     */
    public void deprioritizeFailedRoute(String peerID, String successfulAddress, int numFailedRoutes) {
        if (peerID == null || numFailedRoutes <= 0) return;
        PeerEntry pe = entry(peerID);
        if (pe == null) return;

        synchronized (pe.addresses) {
            if (pe.addresses.size() <= 1) return;
            int successIndex = pe.addresses.indexOf(successfulAddress);
            if (successIndex <= 0) return; // already first, or not present

            List<String> failedRoutes = new ArrayList<>(pe.addresses.subList(0, successIndex));
            pe.addresses.removeAll(failedRoutes);
            pe.addresses.addAll(failedRoutes);

            log.info("[Route Reprioritize] {}: Moved {} failed routes to end, {} now prioritized",
                    shortID(peerID), failedRoutes.size(), successfulAddress);
        }
    }

    /** Number of peers with at least one known address. */
    public int localPeerCount() {
        int n = 0;
        for (PeerEntry pe : peers.values()) {
            if (pe == null) continue;
            synchronized (pe.addresses) {
                if (!pe.addresses.isEmpty()) n++;
            }
        }
        return n;
    }

    /**
     * One-shot migration of addresses and linked peers out of a persisted DataContainer.
     *
     * The DataContainer fields are left in place rather than deleted: DataContainer is
     * Serializable and persisted, and removing fields would fail deserialization of containers
     * already on disk. v0.4.3 records this object silently failing to load once already.
     */
    public void importFromDataContainer(us.anvildevelopment.cxnet.edge.DataContainer dc) {
        if (dc == null) return;
        int addresses = 0;
        int linked = 0;

        if (dc.localPeerAddresses != null) {
            for (Map.Entry<String, List<String>> e : dc.localPeerAddresses.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                // Preserve stored order: these were kept in priority order already, so each is
                // appended as a priority address to avoid the receiving-socket replacement path
                // discarding all but the last.
                for (String addr : e.getValue()) {
                    if (addr == null || addr.isEmpty()) continue;
                    PeerEntry pe = entryOrCreate(e.getKey());
                    synchronized (pe.addresses) {
                        if (!pe.addresses.contains(addr)) {
                            pe.addresses.add(addr);
                            addresses++;
                        }
                    }
                }
            }
        }

        if (dc.LAN != null) {
            for (Map.Entry<String, Node> e : dc.LAN.entrySet()) {
                if (e.getKey() == null) continue;
                PeerEntry pe = entryOrCreate(e.getKey());
                pe.userLinked = true;
                if (pe.node == null && e.getValue() != null) pe.node = e.getValue();
                linked++;
            }
        }

        if (addresses > 0 || linked > 0) {
            log.info("[PeerDirectory] Imported {} addresses and {} user-linked peers from DataContainer",
                    addresses, linked);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lookup and identity management
    // ---------------------------------------------------------------------------------------

    public Node lookup(String cxID, boolean tryImport, boolean sync) throws UnsafeKeywordException {
        return lookup(cxID, tryImport, sync, connectX.cxRoot, connectX);
    }

    public Node lookup(String cxID, boolean tryImport, boolean sync, File cxRoot, ConnectX cx) throws UnsafeKeywordException {
        ConnectX.checkSafety(cxID);
        try {
            if (hv.containsKey(cxID)) return hv.get(cxID);
        } catch (Exception e) {
            //
        }
        Node known = node(cxID);
        if (known != null) return known;

            char s = cxID.charAt(0);
            // Use instance-specific directory if cxRoot provided, otherwise fall back to static peers
            File peerDir = (cxRoot != null) ? new File(cxRoot, "nodemesh") : peersDir;
            // Legacy: Set static peers if needed (for backward compatibility)
            if (peersDir == null && cxRoot != null) peersDir = new File(cxRoot, "nodemesh");
            if (peerDir == null) return null;
            File peerGroup = new File(peerDir, String.valueOf(s));
            if (peerGroup.exists()) {
                File peer = new File(peerGroup, cxID+".cxi");
                if (peer.exists()) {
                        try {
                            Node node = null;
                            if (cx != null) {
                                node = (Node) cx.getSignedObjectNoVerify(peer.toURL().openStream(), Node.class, "cxJSON1");
                                if (node != null) {
                                    PeerEntry pe = entryOrCreate(cxID);
                                    pe.node = node;
                                    pe.diskCached = true;
                                    cx.encryptionProvider.cacheCert(cxID, false, false, connectX);
                                    return node;
                                }
                            }
                        } catch (Exception e) {
                            log.error("Error loading peer from disk", e);
                        }
                }

                // No directly-verified .cxi entry. Check for a cosigned identity (.cxic) instead.
                // This is a local disk read plus cryptographic verification only, no network I/O,
                // so it is safe to run synchronously regardless of the caller's thread.
                File cosigned = new File(peerGroup, cxID + ".cxic");
                if (cosigned.exists() && cx != null && cx.nodeMesh != null) {
                    try {
                        byte[] cosignedBytes = java.nio.file.Files.readAllBytes(cosigned.toPath());
                        Node imported = cx.nodeMesh.verifyAndImportCosignedIdentity(cosignedBytes);
                        if (imported != null) {
                            PeerEntry pe = entryOrCreate(cxID);
                            pe.node = imported;
                            pe.diskCached = true;
                            return imported;
                        }
                    } catch (Exception e) {
                        log.error("Error loading cosigned identity from disk", e);
                    }
                }
            } else if (tryImport & sync) {
                // Network-based peer lookup (PEER_LOOKUP_REQUEST) is intentionally NOT implemented
                // here. lookup() is called from single-threaded and pooled hot paths (EventProcessor,
                // IOThread, OutConnectionController, RetryProcessor) where blocking on a network
                // round-trip could deadlock or starve the pool. Use a dedicated network-aware lookup
                // method from a safe calling context instead (e.g. ConnectX.joinNetworkFromPeers).
            }
        return null;
    }

    public void addNode(Node n) {
        if (Node.validate(n)) {
            // SECURITY: UUID Collision/Spoofing Protection
            // If we already have a node with this cxID, verify the public key matches
            Node existing = hv.containsKey(n.cxID) ? hv.get(n.cxID) : node(n.cxID);

            // If node with this cxID exists, verify public key matches
            if (existing != null && existing.publicKey != null && n.publicKey != null) {
                if (!existing.publicKey.equals(n.publicKey)) {
                    // SECURITY VIOLATION: Same cxID but different public key = spoofing attempt
                    Analytics.addData(AnalyticData.Tear, "UUID spoofing attempt - cxID:" + n.cxID +
                        " existing_key:" + existing.publicKey.substring(0, Math.min(50, existing.publicKey.length())) +
                        " attacker_key:" + n.publicKey.substring(0, Math.min(50, n.publicKey.length())));

                    // Drop the spoofed node - do not add to any directory
                    // Connection will be terminated by caller
                    throw new SecurityException("UUID spoofing: cxID " + n.cxID + " exists with different public key");
                }
                // Keys match - same node re-announcing (e.g. updated address), allow replacement
            }

            PeerEntry pe = entryOrCreate(n.cxID);
            pe.node = n;
            pe.lastSeen = System.currentTimeMillis();
        } else {
            log.warn("[PeerDirectory] Rejected invalid node (cxID={}, publicKey={})",
                n.cxID, n.publicKey != null ? n.publicKey.substring(0, Math.min(16, n.publicKey.length())) : "null");
        }
    }

    /**
     * Register a node as part of the CXNET backbone: EPOCH, a seed-declared hv peer, or a
     * registered backend.
     *
     * Separate from addNode() because backbone membership is authoritative configuration, not
     * something inferred from having met a peer. addNode() used to write hv for every peer from
     * any source, which made hv an unbounded second copy of the directory and meant
     * Seed.fromCurrentPeers() would hand out every peer this node had ever seen.
     */
    public void addBackend(Node n) {
        if (n == null || !Node.validate(n)) return;
        addNode(n);
        hv.put(n.cxID, n);
        PeerEntry pe = entry(n.cxID);
        if (pe != null) pe.backendPinned = true;
    }

    /**
     * Backbone registration that also persists the signed blob, so the identity can be re-shared
     * in a seed without re-signing.
     */
    public void addBackend(Node n, byte[] signed, File cxRoot) {
        if (n == null || !Node.validate(n)) return;
        addNode(n, signed, cxRoot);
        hv.put(n.cxID, n);
        PeerEntry pe = entry(n.cxID);
        if (pe != null) pe.backendPinned = true;
    }

    // Cache of signed node blobs for relaying without re-signing
    public ConcurrentHashMap<String, byte[]> signedNodeCache = new ConcurrentHashMap<>();

    /**
     * Add node with signed blob for persistence (uses peers directory - legacy)
     * @deprecated Use addNode(Node n, byte[] signed, File cxRoot) for instance-specific persistence
     */
    @Deprecated
    public void addNode(Node n, byte[] signed) {
        addNode(n, signed, null);
    }

    /**
     * Add node with signed blob for persistence to instance-specific directory
     * @param n The node to add
     * @param signed The signed node blob for persistence
     * @param cxRoot The instance-specific root directory (e.g., ConnectX-Peer1)
     */
    public void addNode(Node n, byte[] signed, File cxRoot) {
        if (Node.validate(n)) {
            // Add to in-memory store (same as regular addNode)
            addNode(n);

            // Cache the signed blob for relaying
            if (signed != null) {
                signedNodeCache.put(n.cxID, signed);

                // Persist to disk (instance-specific: {cxRoot}/nodemesh/{first_char}/{cxID}.cxi)
                try {
                    File peerDir;
                    if (cxRoot != null) {
                        // Instance-specific directory
                        peerDir = new File(cxRoot, "nodemesh");
                    } else {
                        // Fallback to static peers directory (legacy behavior)
                        peerDir = peersDir;
                    }

                    if (peerDir != null) {
                        char firstChar = n.cxID.charAt(0);
                        File peerGroup = new File(peerDir, String.valueOf(firstChar));
                        if (!peerGroup.exists()) {
                            peerGroup.mkdirs();
                        }

                        File peerFile = new File(peerGroup, n.cxID + ".cxi");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(peerFile);
                        fos.write(signed);
                        fos.flush();
                        fos.close();

                        log.info("[PeerDirectory] Persisted signed node: {} to {} ({} bytes)",
                            shortID(n.cxID), peerFile.getAbsolutePath(), signed.length);
                    }
                } catch (Exception e) {
                    log.error("[PeerDirectory] Failed to persist node {}: {}", n.cxID, e.getMessage());
                }
            }
        } else {
            log.warn("[PeerDirectory] Rejected invalid node for signed add (cxID={}, publicKey={})",
                n.cxID, n.publicKey != null ? n.publicKey.substring(0, Math.min(16, n.publicKey.length())) : "null");
        }
    }

    /**
     * Tracks an in-flight network identity lookup for one cxID: retry bookkeeping plus any
     * IOJobs waiting on resolution. Never blocks anything; callers register a completion job
     * and move on. ConnectX.requestPeerLookupAsync() creates these; NodeMesh's
     * PEER_LOOKUP_RESPONSE handler resolves them by queuing each registered job onto
     * jobQueue once verification succeeds.
     */
    public static class PendingPeerLookup {
        public final boolean epochOnly;
        public final long firstAttemptAt;
        public volatile long lastAttemptAt;
        public volatile int attempts;
        public final java.util.List<us.anvildevelopment.cxnet.io.IOJob> completionJobs =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        public PendingPeerLookup(boolean epochOnly) {
            this.epochOnly = epochOnly;
            this.firstAttemptAt = System.currentTimeMillis();
            this.lastAttemptAt = this.firstAttemptAt;
            this.attempts = 0;
        }
    }

    // Pending network identity lookups: targetCXID -> retry state + registered completion jobs.
    // Populated by ConnectX.requestPeerLookupAsync(); resolved (and removed) by NodeMesh's
    // PEER_LOOKUP_RESPONSE handler once a verified cosigned identity arrives, or expired by
    // the periodic retry sweep if no answer arrives within the retry limit.
    public ConcurrentHashMap<String, PendingPeerLookup> pendingPeerLookups = new ConcurrentHashMap<>();

    // Cache of cosigned identity blobs: an already-self-signed Node blob wrapped with an
    // additional outer signature from EPOCH or a trusted CXNET backend. Used to bootstrap
    // trust for nodes this peer has not met directly. Stored alongside regular signed nodes
    // with a distinct extension (.cxic) so the same directory walk/relay code can be reused,
    // and so a cosigned entry can never be confused with or overwrite a directly-verified
    // .cxi entry. See Node.signedAt for the trust and replacement rules.
    public ConcurrentHashMap<String, byte[]> cosignedNodeCache = new ConcurrentHashMap<>();

    /**
     * Store a cosigned identity blob for relaying to other peers.
     * Does NOT add the node to PeerDirectory or signedNodeCache. The outer cosignature must
     * be verified and the inner blob imported via the normal addNode(Node, byte[], File) path
     * before this identity is trusted for anything beyond further relay.
     *
     * @param targetCXID  the cxID this cosigned blob identifies
     * @param cosignedBlob the outer-signed bytes (inner payload is the target's self-signed Node blob)
     * @param cxRoot      instance-specific root directory
     */
    public void addCosignedNode(String targetCXID, byte[] cosignedBlob, File cxRoot) {
        if (targetCXID == null || cosignedBlob == null) return;
        cosignedNodeCache.put(targetCXID, cosignedBlob);
        try {
            File peerDir = cxRoot != null ? new File(cxRoot, "nodemesh") : peersDir;
            if (peerDir == null) return;
            char firstChar = targetCXID.charAt(0);
            File peerGroup = new File(peerDir, String.valueOf(firstChar));
            if (!peerGroup.exists()) peerGroup.mkdirs();
            File peerFile = new File(peerGroup, targetCXID + ".cxic");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(peerFile);
            fos.write(cosignedBlob);
            fos.flush();
            fos.close();
            log.info("[PeerDirectory] Persisted cosigned identity: {} to {} ({} bytes)",
                shortID(targetCXID), peerFile.getAbsolutePath(), cosignedBlob.length);
        } catch (Exception e) {
            log.error("[PeerDirectory] Failed to persist cosigned identity {}: {}", targetCXID, e.getMessage());
        }
    }

    /**
     * Get a cosigned identity blob from cache or disk, for relaying to a peer that requested it.
     * Returns the raw outer-signed bytes. Callers must verify the outer signature against a
     * trusted key before treating the inner Node as anything more than an unverified claim.
     *
     * @param cxID the cxID to look up
     * @return cosigned bytes, or null if not available
     */
    public byte[] getCosignedNode(String cxID) {
        if (cosignedNodeCache != null && cosignedNodeCache.containsKey(cxID)) {
            return cosignedNodeCache.get(cxID);
        }
        if (peersDir != null) {
            try {
                char firstChar = cxID.charAt(0);
                File peerGroup = new File(peersDir, String.valueOf(firstChar));
                File peerFile = new File(peerGroup, cxID + ".cxic");
                if (peerFile.exists()) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(peerFile);
                    byte[] cosignedBytes = fis.readAllBytes();
                    fis.close();
                    if (cosignedNodeCache == null) cosignedNodeCache = new ConcurrentHashMap<>();
                    cosignedNodeCache.put(cxID, cosignedBytes);
                    return cosignedBytes;
                }
            } catch (Exception e) {
                log.error("[PeerDirectory] Failed to load cosigned identity {}: {}", cxID, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Get signed node blob from cache or disk
     * Returns the original signed bytes for relaying without re-signing
     * @param cxID The node ID to get signed blob for
     * @return Signed node bytes, or null if not available
     */
    public byte[] getSignedNode(String cxID) {
        // Check memory cache first
        if (signedNodeCache != null && signedNodeCache.containsKey(cxID)) {
            return signedNodeCache.get(cxID);
        }

        // Try loading from disk
        if (peersDir != null) {
            try {
                char firstChar = cxID.charAt(0);
                File peerGroup = new File(peersDir, String.valueOf(firstChar));
                File peerFile = new File(peerGroup, cxID + ".cxi");

                if (peerFile.exists()) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(peerFile);
                    byte[] signedBytes = fis.readAllBytes();
                    fis.close();

                    // Cache for future use
                    if (signedNodeCache == null) signedNodeCache = new ConcurrentHashMap<>();
                    signedNodeCache.put(cxID, signedBytes);

                    return signedBytes;
                }
            } catch (Exception e) {
                log.error("[PeerDirectory] Failed to load signed node {}: {}", cxID, e.getMessage());
            }
        }

        return null;
    }

    /**
     * Remove a node from all peer directories (memory only - legacy)
     * @deprecated Use removeNode(String cxID, File cxRoot) to also remove from filesystem
     * @param cxID The node ID to remove
     */
    @Deprecated
    public void removeNode(String cxID) {
        removeNode(cxID, null);
    }

    /**
     * Remove a node from all peer directories (memory AND filesystem)
     * Used for rollback when NewNode/CXHELLO signature verification fails
     * @param cxID The node ID to remove
     * @param cxRoot The instance-specific root directory (e.g., ConnectX-Peer1)
     */
    public void removeNode(String cxID, File cxRoot) {
        if (cxID == null) return;

        peers.remove(cxID);
        if (hv != null) hv.remove(cxID);
        if (signedNodeCache != null) signedNodeCache.remove(cxID);

        // Remove from filesystem
        try {
            File peerDir;
            if (cxRoot != null) {
                // Instance-specific directory
                peerDir = new File(cxRoot, "nodemesh");
            } else {
                // Fallback to static peers directory (legacy behavior)
                peerDir = peersDir;
            }

            if (peerDir != null) {
                char firstChar = cxID.charAt(0);
                File peerGroup = new File(peerDir, String.valueOf(firstChar));
                File peerFile = new File(peerGroup, cxID + ".cxi");

                if (peerFile.exists()) {
                    boolean deleted = peerFile.delete();
                    if (deleted) {
                        log.info("[PeerDirectory] Removed node from filesystem: {} at {}", cxID, peerFile.getAbsolutePath());
                    } else {
                        log.error("[PeerDirectory] Failed to delete file: {}", peerFile.getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[PeerDirectory] Error removing node from filesystem: {} - {}", cxID, e.getMessage());
        }

        log.info("[PeerDirectory] Removed node from memory: {}", cxID);
    }

    public boolean stableConnection() {
        return true;
    }

    /**
     * Get all known addresses for a peer in priority order: recorded addresses first (advertised
     * CXHELLO addresses, then observed sockets), then the Node's own advertised address.
     *
     * Previously this had to join PeerDirectory's four maps against
     * DataContainer.localPeerAddresses on every call. Both now live on the same PeerEntry.
     *
     * @param cxID The peer ID to look up
     * @param connectX retained for signature compatibility; no longer required
     * @return List of all known addresses for this peer, empty list if none found
     */
    public java.util.List<String> getAllAddresses(String cxID, ConnectX connectX) {
        java.util.List<String> addresses = new ArrayList<>();
        if (cxID == null) return addresses;

        java.util.Set<String> dedup = new java.util.HashSet<>();

        // Recorded addresses first: advertised CXHELLO addresses, then observed sockets.
        PeerEntry pe = entry(cxID);
        if (pe != null) {
            synchronized (pe.addresses) {
                for (String addr : pe.addresses) {
                    if (addr != null && !addr.isEmpty() && dedup.add(addr)) addresses.add(addr);
                }
            }
            addAddr(addresses, dedup, pe.node);
        }

        // Then the backbone entry. Deliberately NOT gated on having a PeerEntry: a node can be
        // registered straight into hv without one (BootstrapServerTest does exactly that for
        // EPOCH), and the previous implementation searched hv for Node.addr alongside the other
        // three maps. Returning early on a missing PeerEntry resolved such a peer to zero
        // addresses, which silently broke every targeted send to it -- CXS, E2E and streams all
        // route through this method.
        addAddr(addresses, dedup, hv.get(cxID));

        if (log.isDebugEnabled()) {
            log.debug("[getAllAddresses] Total addresses for {}: {}", shortID(cxID), addresses.size());
        }
        return addresses;
    }

    /** Append a Node's advertised address if it has one and it is not already present. */
    private static void addAddr(List<String> out, java.util.Set<String> dedup, Node n) {
        if (n == null || n.addr == null || n.addr.isEmpty()) return;
        if (dedup.add(n.addr)) out.add(n.addr);
    }

    private static String shortID(String cxID) {
        if (cxID == null) return "null";
        return cxID.length() >= 8 ? cxID.substring(0, 8) : cxID;
    }

    // ---------------------------------------------------------------------------------------
    // Deprecated Node-map projections
    // ---------------------------------------------------------------------------------------

    /**
     * Presents the PeerEntry store as the Map&lt;String,Node&gt; that the old seen/lan/peerCache
     * fields were, so the existing call sites keep compiling and behaving while they migrate.
     *
     * Reads are filtered by mode; writes set the corresponding flag, so lan.put() still means
     * "this peer is on a local subnet". values()/keySet()/entrySet() return snapshots: callers
     * iterate these while sending, and several mutate the directory as they go.
     */
    static class NodeMapView extends AbstractMap<String, Node> implements Serializable {
        static final int ALL = 0;
        static final int LOCAL_SUBNET = 1;
        static final int DISK_CACHED = 2;

        private final PeerDirectory owner;
        private final int mode;

        NodeMapView(PeerDirectory owner, int mode) {
            this.owner = owner;
            this.mode = mode;
        }

        private boolean matches(PeerEntry pe) {
            if (pe == null || pe.node == null) return false;
            switch (mode) {
                case LOCAL_SUBNET: return pe.localSubnet;
                case DISK_CACHED: return pe.diskCached;
                default: return true;
            }
        }

        private void tag(PeerEntry pe) {
            switch (mode) {
                case LOCAL_SUBNET: pe.localSubnet = true; break;
                case DISK_CACHED: pe.diskCached = true; break;
                default: break;
            }
        }

        @Override
        public Node get(Object key) {
            if (!(key instanceof String)) return null;
            PeerEntry pe = owner.peers.get(key);
            return matches(pe) ? pe.node : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public Node put(String key, Node value) {
            if (key == null) return null;
            PeerEntry pe = owner.entryOrCreate(key);
            Node previous = pe.node;
            pe.node = value;
            pe.lastSeen = System.currentTimeMillis();
            tag(pe);
            return previous;
        }

        @Override
        public Node remove(Object key) {
            if (!(key instanceof String)) return null;
            PeerEntry pe = owner.peers.remove(key);
            return pe != null ? pe.node : null;
        }

        @Override
        public int size() {
            int n = 0;
            for (PeerEntry pe : owner.peers.values()) if (matches(pe)) n++;
            return n;
        }

        @Override
        public boolean isEmpty() {
            for (PeerEntry pe : owner.peers.values()) if (matches(pe)) return false;
            return true;
        }

        @Override
        public java.util.Collection<Node> values() {
            List<Node> out = new ArrayList<>();
            for (PeerEntry pe : owner.peers.values()) if (matches(pe)) out.add(pe.node);
            return out;
        }

        @Override
        public Set<String> keySet() {
            Set<String> out = new java.util.LinkedHashSet<>();
            for (Map.Entry<String, PeerEntry> e : owner.peers.entrySet()) {
                if (matches(e.getValue())) out.add(e.getKey());
            }
            return out;
        }

        @Override
        public Set<Map.Entry<String, Node>> entrySet() {
            final List<Map.Entry<String, Node>> snapshot = new ArrayList<>();
            for (Map.Entry<String, PeerEntry> e : owner.peers.entrySet()) {
                if (matches(e.getValue())) {
                    snapshot.add(new SimpleEntry<>(e.getKey(), e.getValue().node));
                }
            }
            return new AbstractSet<Map.Entry<String, Node>>() {
                @Override public Iterator<Map.Entry<String, Node>> iterator() { return snapshot.iterator(); }
                @Override public int size() { return snapshot.size(); }
            };
        }
    }
}
