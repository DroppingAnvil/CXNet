/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.edge;

import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.events.NetworkEvent;
import us.anvildevelopment.util.tools.database.annotations.MemoryOnly;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkBlock implements Serializable {
    public Long block;
    public Long chain; // Chain ID (c1, c2, or c3) - needed for blockchain sync

    /**
     * Network events stored as signed blobs - PERSISTED TO DISK
     * This is the source of truth - original cryptographically-signed bytes
     * Key: Event index, Value: Signed blob (NetworkEvent serialized + signature)
     *
     * CRITICAL: This map contains SIGNED BLOBS, not deserialized NetworkEvent objects
     * - Always verify signature before accepting
     * - Never modify blobs after storage (breaks signature)
     * - Use addEvent() to add new events with verification
     * - Use getEvent() to access deserialized events
     */
    public Map<Integer, byte[]> networkEvents;

    /**
     * Deserialized event cache - MEMORY ONLY, NOT PERSISTED
     * Events are deserialized on-demand from networkEvents (signed blobs)
     * This is a performance cache to avoid repeated deserialization
     *
     * Populated by:
     * - addEvent() when event is added
     * - getEvent() on first access (lazy loading)
     */
    @MemoryOnly
    public Map<Integer, NetworkEvent> deserializedEvents;

    // Default constructor for Jackson deserialization
    public NetworkBlock() {
        networkEvents = new ConcurrentHashMap<>();
        deserializedEvents = new ConcurrentHashMap<>();
    }

    public NetworkBlock(Long block) {
        this.block = block;
        networkEvents = new ConcurrentHashMap<>();
        deserializedEvents = new ConcurrentHashMap<>();
    }

    public NetworkBlock(Long block, Long chain) {
        this.block = block;
        this.chain = chain;
        networkEvents = new ConcurrentHashMap<>();
        deserializedEvents = new ConcurrentHashMap<>();
    }

    /**
     * Add event to block with verification
     *
     * Process:
     * 1. Store signed blob in local variable
     * 2. Peek to get sender cxID (stripSignature without verify)
     * 3. Verify signature using sender's public key
     * 4. Deserialize into NetworkEvent
     * 5. Add to both maps (signed blob + deserialized cache)
     *
     * @param index Event index in block
     * @param signedBlob Original signed bytes
     * @param connectX ConnectX instance for verification helpers
     * @return true if event added successfully, false if verification failed
     */
    public boolean addEvent(int index, byte[] signedBlob, ConnectX connectX) {
        try {
            // Peek sender cxID
            String senderID = peekSenderID(signedBlob, connectX);
            if (senderID == null) {
                System.err.println("[NetworkBlock] Failed to peek sender ID");
                return false;
            }

            // Verify and strip signature
            java.io.ByteArrayInputStream verifyStream = new java.io.ByteArrayInputStream(signedBlob);
            java.io.ByteArrayOutputStream verifiedOutput = new java.io.ByteArrayOutputStream();
            boolean verified = connectX.encryptionProvider.verifyAndStrip(verifyStream, verifiedOutput, senderID);
            verifyStream.close();

            if (!verified) {
                System.err.println("[NetworkBlock] Signature verification FAILED for " +
                    (senderID.length() >= 8 ? senderID.substring(0, 8) : senderID));
                return false;
            }

            // Deserialize
            String verifiedJson = new String(verifiedOutput.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            NetworkEvent event = (NetworkEvent) ConnectX.deserialize("cxJSON1", verifiedJson, NetworkEvent.class);

            if (event == null) {
                System.err.println("[NetworkBlock] Deserialization failed");
                return false;
            }

            // Add to maps
            networkEvents.put(index, signedBlob);         // Persisted
            deserializedEvents.put(index, event);         // Memory cache

            return true;
        } catch (Exception e) {
            System.err.println("[NetworkBlock] Error adding event: " + e.getMessage());
            return false;
        }
    }

    /**
     * Prepare ALL events in block - verify and deserialize into memory cache
     * Call before accessing multiple events (e.g., blockchain replay)
     *
     * @param connectX ConnectX instance
     * @return Number of events successfully prepared
     */
    public int prepare(ConnectX connectX) {
        int prepared = 0;
        for (Integer index : networkEvents.keySet()) {
            if (prepare(index, connectX)) {
                prepared++;
            }
        }
        LoggerFactory.getLogger(NetworkBlock.class).info("[NetworkBlock] Prepared " + prepared + "/" + networkEvents.size() +
                " events for block " + block);
        return prepared;
    }

    /**
     * Prepare single event - verify and deserialize into memory cache
     *
     * @param index Event index
     * @param connectX ConnectX instance
     * @return true if prepared successfully
     */
    public boolean prepare(int index, ConnectX connectX) {
        // Already prepared?
        if (deserializedEvents.containsKey(index)) {
            return true;
        }

        byte[] signedBlob = networkEvents.get(index);
        if (signedBlob == null) {
            return false;
        }

        try {
            // Peek sender cxID
            String senderID = peekSenderID(signedBlob, connectX);
            if (senderID == null) {
                return false;
            }

            // Verify signature
            java.io.ByteArrayInputStream verifyStream = new java.io.ByteArrayInputStream(signedBlob);
            java.io.ByteArrayOutputStream verifiedOutput = new java.io.ByteArrayOutputStream();
            boolean verified = connectX.encryptionProvider.verifyAndStrip(verifyStream, verifiedOutput, senderID);
            verifyStream.close();

            if (!verified) {
                LoggerFactory.getLogger(NetworkBlock.class).error("[NetworkBlock] Event {} verification FAILED", index);
                return false;
            }

            // Deserialize
            String verifiedJson = new String(verifiedOutput.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            NetworkEvent event = (NetworkEvent) ConnectX.deserialize("cxJSON1", verifiedJson, NetworkEvent.class);

            if (event != null) {
                deserializedEvents.put(index, event);
                return true;
            }

            return false;
        } catch (Exception e) {
            LoggerFactory.getLogger(NetworkBlock.class).error("[NetworkBlock] Error preparing event {}: {}", index, e.getMessage());
            return false;
        }
    }

    /**
     * Peek sender cxID from signed blob without verification
     * Uses stripSignature (not verifyAndStrip) to peek at unverified data
     *
     * @param signedBlob Signed event blob
     * @param connectX ConnectX instance
     * @return Sender cxID or null
     */
    private String peekSenderID(byte[] signedBlob, ConnectX connectX) {
        try {
            // Strip signature without verifying
            java.io.ByteArrayInputStream peekStream = new java.io.ByteArrayInputStream(signedBlob);
            java.io.ByteArrayOutputStream peekOutput = new java.io.ByteArrayOutputStream();
            connectX.encryptionProvider.stripSignature(peekStream, peekOutput);
            peekStream.close();

            // Deserialize unverified event to peek at path
            String unverifiedJson = peekOutput.toString("UTF-8");
            NetworkEvent unverifiedEvent = (NetworkEvent) ConnectX.deserialize(
                "cxJSON1", unverifiedJson, NetworkEvent.class);

            // Extract sender oCXID from path - REQUIRED for signature verification
            // SECURITY: Must use oCXID (origin), never cxID (destination) to prevent spoofing
            if (unverifiedEvent != null && unverifiedEvent.p != null && unverifiedEvent.p.oCXID != null) {
                return unverifiedEvent.p.oCXID;
            }

            LoggerFactory.getLogger(NetworkBlock.class).error("[NetworkBlock] Security failure: Event missing oCXID (origin sender ID)");
            return null;
        } catch (Exception e) {
            LoggerFactory.getLogger(NetworkBlock.class).error("[NetworkBlock] Error peeking sender: {}", e.getMessage());
            return null;
        }
    }
}
