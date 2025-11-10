/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.exceptions.UnsafeKeywordException;

import java.io.File;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;

public class PeerDirectory implements Serializable {
    public static ConcurrentHashMap<String,Node> peerCache;
    public static ConcurrentHashMap<String,Node> seen;
    public static ConcurrentHashMap<String, Node> lan;
    public static ConcurrentHashMap<String,Node> hv;
    public static File peers;


    //TODO
    //Writing node lookup and create account implementation, next up create peer finding event
    public static Node lookup(String cxID, boolean tryImport, boolean sync) throws UnsafeKeywordException {
        return lookup(cxID, tryImport, sync, null, null);
    }

    public static Node lookup(String cxID, boolean tryImport, boolean sync, File cxRoot, ConnectX cx) throws UnsafeKeywordException {
        ConnectX.checkSafety(cxID);
        if (hv.containsKey(cxID)) return hv.get(cxID);
        if (seen.containsKey(cxID)) return seen.get(cxID);
        if (peerCache.containsKey(cxID)) return peerCache.get(cxID);
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
            //TODO implement node addition to cache/persistence
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

    public static boolean stableConnection() {
        return true;
    }
}
