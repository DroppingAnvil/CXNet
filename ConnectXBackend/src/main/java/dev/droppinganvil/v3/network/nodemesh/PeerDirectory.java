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
import java.util.concurrent.ConcurrentHashMap;

public class PeerDirectory implements Serializable {
    public static ConcurrentHashMap<String,Node> peerCache = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String,Node> seen = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, Node> lan = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String,Node> hv = new ConcurrentHashMap<>();
    public static File peers;


    //TODO
    //Writing node lookup and create account implementation, next up create peer finding event
    public static Node lookup(String cxID, boolean tryImport, boolean sync) throws UnsafeKeywordException {
        return lookup(cxID, tryImport, sync, null, null);
    }

    public static Node lookup(String cxID, boolean tryImport, boolean sync, File cxRoot, ConnectX cx) throws UnsafeKeywordException {
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
            if (peers == null && cxRoot != null) peers = new File(cxRoot, "nodemesh");
            if (peers == null) return null;
            File peerGroup = new File(peers, String.valueOf(s));
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

    public static void addNode(Node n) {
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

    public static void addNode(Node n, byte[] signed) {
        if (Node.validate(n)) {
            //TODO implement node persistence with signed bytes
        } else {
            throw new IllegalStateException();
        }
    }

    /**
     * Remove a node from all peer directories
     * Used for rollback when NewNode signature verification fails
     * @param cxID The node ID to remove
     */
    public static void removeNode(String cxID) {
        if (cxID == null) return;

        // Remove from all peer directories
        if (hv != null) hv.remove(cxID);
        if (seen != null) seen.remove(cxID);
        if (lan != null) lan.remove(cxID);
        if (peerCache != null) peerCache.remove(cxID);

        System.out.println("[PeerDirectory] Removed node: " + cxID);
    }

    public static boolean stableConnection() {
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
    public static java.util.List<String> getAllAddresses(String cxID, ConnectX connectX) {
        java.util.List<String> addresses = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>(); // Track duplicates

        if (cxID == null) return addresses;

        // PRIORITY 1: CXHELLO discovered LAN addresses (highest priority for P2P)
        if (connectX != null && connectX.dataContainer != null) {
            java.util.List<String> lanAddresses = connectX.dataContainer.getLocalPeerAddresses(cxID);
            for (String addr : lanAddresses) {
                if (addr != null && !addr.isEmpty() && !seen.contains(addr)) {
                    addresses.add(addr);
                    seen.add(addr);
                }
            }
        }

        // PRIORITY 2: Check all PeerDirectory maps for Node.addr
        Node[] nodesToCheck = new Node[] {
            lan.get(cxID),      // LAN discovered nodes
            hv.get(cxID),       // High-value peers (seeds, registered)
            PeerDirectory.seen.get(cxID), // Recently seen peers
            peerCache.get(cxID) // Cached peer info
        };

        for (Node node : nodesToCheck) {
            if (node != null && node.addr != null && !node.addr.isEmpty() && !seen.contains(node.addr)) {
                addresses.add(node.addr);
                seen.add(node.addr);
            }
        }

        return addresses;
    }
}
