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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PeerDirectory implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(PeerDirectory.class);

    public ConcurrentHashMap<String,Node> peerCache = new ConcurrentHashMap<>();
    /**
     * For tracking nodes we have connected to directly over the internet, in the future this might be changed to a set of cxIDs
     */
    public ConcurrentHashMap<String,Node> seen = new ConcurrentHashMap<>();
    /**
     * More resource friendly way to store seen peers, by reference.
     * Thread-safe: backed by ConcurrentHashMap for O(1) contains/add under concurrent IOThreads.
     * //TODO Optimize older uses of seen map
     */
    public Set<String> seenCXIDs = ConcurrentHashMap.newKeySet();
    public ConcurrentHashMap<String, Node> lan = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String,Node> hv = new ConcurrentHashMap<>();
    public File peers;
    @MemoryOnly
    public ConnectX connectX = null;
    public PeerDirectory(ConnectX cx) {
        if (connectX == null) {
            connectX = cx;
        }
    }
    //TODO
    //Writing node lookup and create account implementation, next up create peer finding event, wow...
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
        try {
            if (seen.containsKey(cxID)) return seen.get(cxID);
        } catch (Exception exception) {}
        try {
            if (peerCache.containsKey(cxID)) return peerCache.get(cxID);
        } catch (Exception e) {
//            throw new RuntimeException(e);
        }
            char s = cxID.charAt(0);
            // Use instance-specific directory if cxRoot provided, otherwise fall back to static peers
            File peerDir = (cxRoot != null) ? new File(cxRoot, "nodemesh") : peers;
            // Legacy: Set static peers if needed (for backward compatibility)
            if (peers == null && cxRoot != null) peers = new File(cxRoot, "nodemesh");
            if (peerDir == null) return null;
            File peerGroup = new File(peerDir, String.valueOf(s));
            if (peerGroup.exists()) {
                File peer = new File(peerGroup, cxID+".cxi");
                if (peer.exists()) {
                    //if (sync) {
                        try {
                            Node node = null;
                            if (cx != null) {
                                node = (Node) cx.getSignedObjectNoVerify(peer.toURL().openStream(), Node.class, "cxJSON1");
                                if (node != null) {
                                    peerCache.put(cxID, node);
                                    seen.put(cxID, node);
                                    cx.encryptionProvider.cacheCert(cxID, false, false, connectX);
                                    return node;
                                }
                            }
                        } catch (Exception e) {
                            log.error("Error loading peer from disk", e);
                        }
                   // } else {
                        //TODO async
                  //  }
                }
            } else if (tryImport & sync) {
                //TODO implement peer import
            }
        return null;
    }

    public void addNode(Node n) {
        if (Node.validate(n)) {
            // Lazy initialization - ensure maps are initialized before use
            if (seen == null) seen = new ConcurrentHashMap<>();
            if (hv == null) hv = new ConcurrentHashMap<>();
            if (lan == null) lan = new ConcurrentHashMap<>();
            if (peerCache == null) peerCache = new ConcurrentHashMap<>();

            // SECURITY: UUID Collision/Spoofing Protection
            // If we already have a node with this cxID, verify the public key matches
            Node existing = null;

            // Check all peer directories for existing node with same cxID
            if (hv.containsKey(n.cxID)) {
                existing = hv.get(n.cxID);
            } else if (seen.containsKey(n.cxID)) {
                existing = seen.get(n.cxID);
            } else if (peerCache.containsKey(n.cxID)) {
                existing = peerCache.get(n.cxID);
            }

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

            // Add to high-value peer directory for routing
            // Peers added via addNode() (from seeds, REGISTER_NODE, etc) are considered hv peers
            hv.put(n.cxID, n);

            // Also track in seen for last-seen timestamp tracking
            seen.put(n.cxID, n);
        } else {
            log.warn("[PeerDirectory] Rejected invalid node (cxID={}, publicKey={})",
                n.cxID, n.publicKey != null ? n.publicKey.substring(0, Math.min(16, n.publicKey.length())) : "null");
        }
    }

    // Cache of signed node blobs for relaying without re-signing
    public ConcurrentHashMap<String, byte[]> signedNodeCache = new ConcurrentHashMap<>();

    /**
     * Add node with signed blob for persistence (uses peers directory - legacy)
     * @deprecated Use addNode(Node n, byte[] signed, File cxRoot) for instance-specific persistence
     */
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
            // Add to in-memory directories (same as regular addNode)
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
                        peerDir = peers;
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
                            n.cxID.substring(0, 8), peerFile.getAbsolutePath(), signed.length);
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
        if (peers != null) {
            try {
                char firstChar = cxID.charAt(0);
                File peerGroup = new File(peers, String.valueOf(firstChar));
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

        // Remove from all peer directories
        if (hv != null) hv.remove(cxID);
        if (seen != null) seen.remove(cxID);
        if (lan != null) lan.remove(cxID);
        if (peerCache != null) peerCache.remove(cxID);
        if (signedNodeCache != null) signedNodeCache.remove(cxID);

        // Remove from filesystem
        try {
            File peerDir;
            if (cxRoot != null) {
                // Instance-specific directory
                peerDir = new File(cxRoot, "nodemesh");
            } else {
                // Fallback to static peers directory (legacy behavior)
                peerDir = peers;
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
     * Get all known addresses for a peer from all available sources:
     * - Node addresses from PeerDirectory (hv, seen, lan, peerCache)
     * - CXHELLO discovered addresses from DataContainer
     * - Bridge addresses
     *
     * Returns addresses in priority order (direct/LAN first, then bridges)
     *
     * @param cxID The peer ID to look up
     * @param connectX ConnectX instance (for DataContainer access)
     * @return List of all known addresses for this peer, empty list if none found
     */
    public java.util.List<String> getAllAddresses(String cxID, ConnectX connectX) {
        java.util.List<String> addresses = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>(); // Track duplicates

        if (cxID == null) return addresses;

        // PRIORITY 1: CXHELLO discovered LAN addresses (highest priority for P2P)
        if (connectX != null && connectX.dataContainer != null) {
            java.util.List<String> lanAddresses = connectX.dataContainer.getLocalPeerAddresses(cxID);
            log.info("[getAllAddresses] Peer {}: Found {} LAN addresses in DataContainer", cxID.substring(0, 8), lanAddresses.size());
            for (String addr : lanAddresses) {
                if (addr != null && !addr.isEmpty() && !seen.contains(addr)) {
                    addresses.add(addr);
                    seen.add(addr);
                    log.info("[getAllAddresses]   - LAN: {}", addr);
                }
            }
        }

        // PRIORITY 2: Check all PeerDirectory maps for Node.addr
        Node[] nodesToCheck = new Node[] {
            lan.get(cxID),      // LAN discovered nodes
            hv.get(cxID),       // High-value peers (seeds, registered)
            this.seen.get(cxID),     // Recently seen peers
            peerCache.get(cxID) // Cached peer info
        };

        for (Node node : nodesToCheck) {
            if (node != null && node.addr != null && !node.addr.isEmpty() && !seen.contains(node.addr)) {
                addresses.add(node.addr);
                seen.add(node.addr);
                log.info("[getAllAddresses]   - Node: {}", node.addr);
            }
        }

        log.info("[getAllAddresses] Total addresses for {}: {}", cxID.substring(0, 8), addresses.size());
        return addresses;
    }
}
