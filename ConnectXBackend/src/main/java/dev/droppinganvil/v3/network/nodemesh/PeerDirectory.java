/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.analytics.AnalyticData;
import dev.droppinganvil.v3.analytics.Analytics;
import dev.droppinganvil.v3.exceptions.UnsafeKeywordException;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class PeerDirectory implements Serializable {
    public ConcurrentHashMap<String,Node> peerCache = new ConcurrentHashMap<>();
    /**
     * For tracking nodes we have connected to directly over the internet, in the future this might be changed to a set of cxIDs
     */
    public ConcurrentHashMap<String,Node> seen = new ConcurrentHashMap<>();
    /**
     * More resource friendly way to store seen peers, by reference
     * //TODO Optimize older uses of seen map
     */
    public ArrayList<String> seenCXIDs = new ArrayList<>();
    public ConcurrentHashMap<String, Node> lan = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String,Node> hv = new ConcurrentHashMap<>();
    public File peers;
    public ConnectX connectX = null;

    public PeerDirectory(ConnectX cx) {
        if (connectX == null) {
            connectX = cx;
        }
    }
    //TODO
    //Writing node lookup and create account implementation, next up create peer finding event
    public Node lookup(String cxID, boolean tryImport, boolean sync) throws UnsafeKeywordException {
        return lookup(cxID, tryImport, sync, null, null);
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
                                node = (Node) cx.getSignedObject(cxID, peer.toURL().openStream(), Node.class, "cxJSON1");
                                cx.encryptionProvider.cacheCert(cxID, false, false);
                            }
                            if (node != null) {
                                peerCache.put(cxID, node);
                                return node;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
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
                // Keys match - this is the same node, update the entry
            }

            // Add to high-value peer directory for routing
            // Peers added via addNode() (from seeds, REGISTER_NODE, etc) are considered hv peers
            hv.put(n.cxID, n);

            // Also track in seen for last-seen timestamp tracking
            seen.put(n.cxID, n);
        } else {
            throw new IllegalStateException();
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

                        System.out.println("[PeerDirectory] Persisted signed node: " + n.cxID.substring(0, 8) +
                            " to " + peerFile.getAbsolutePath() + " (" + signed.length + " bytes)");
                    }
                } catch (Exception e) {
                    System.err.println("[PeerDirectory] Failed to persist node " + n.cxID + ": " + e.getMessage());
                }
            }
        } else {
            throw new IllegalStateException();
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
                System.err.println("[PeerDirectory] Failed to load signed node " + cxID + ": " + e.getMessage());
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
                        System.out.println("[PeerDirectory] Removed node from filesystem: " + cxID + " at " + peerFile.getAbsolutePath());
                    } else {
                        System.err.println("[PeerDirectory] Failed to delete file: " + peerFile.getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PeerDirectory] Error removing node from filesystem: " + cxID + " - " + e.getMessage());
        }

        System.out.println("[PeerDirectory] Removed node from memory: " + cxID);
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
            System.out.println("[getAllAddresses] Peer " + cxID.substring(0, 8) + ": Found " + lanAddresses.size() + " LAN addresses in DataContainer");
            for (String addr : lanAddresses) {
                if (addr != null && !addr.isEmpty() && !seen.contains(addr)) {
                    addresses.add(addr);
                    seen.add(addr);
                    System.out.println("[getAllAddresses]   - LAN: " + addr);
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
                System.out.println("[getAllAddresses]   - Node: " + node.addr);
            }
        }

        System.out.println("[getAllAddresses] Total addresses for " + cxID.substring(0, 8) + ": " + addresses.size());
        return addresses;
    }
}
