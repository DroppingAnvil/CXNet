package us.anvildevelopment.cxnet.network;

import us.anvildevelopment.cxnet.edge.NetworkBlock;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks multi-peer block requests and responses for zero trust consensus
 *
 * In zero trust mode, blocks are requested from multiple peers and compared
 * to reach consensus through majority voting rather than trusting a single NMI source.
 *
 * Usage:
 * 1. Create request: tracker.createRequest(networkID, chainID, blockID, peers)
 * 2. Record responses: tracker.recordResponse(requestKey, peerID, block)
 * 3. Check consensus: tracker.checkConsensus(requestKey)
 * 4. Get result: tracker.getConsensusBlock(requestKey)
 */
public class BlockConsensusTracker {

    /**
     * Tracks a single block request across multiple peers
     */
    public static class BlockRequest {
        public String networkID;
        public Long chainID;
        public Long blockID;
        public Set<String> requestedPeers;  // Peers we requested from
        public Map<String, NetworkBlock> responses;  // Responses received: peerID -> block
        public Map<String, String> blockHashes;  // Block hash per peer for comparison
        public long requestTime;
        public long timeoutMs;
        public boolean consensusReached;
        public NetworkBlock consensusBlock;

        // Network-specific consensus configuration
        public int minPeers;
        public double minResponseRate;
        public double consensusThreshold;

        public BlockRequest(String networkID, Long chainID, Long blockID, Set<String> peers,
                          long timeoutMs, int minPeers, double minResponseRate, double consensusThreshold) {
            this.networkID = networkID;
            this.chainID = chainID;
            this.blockID = blockID;
            this.requestedPeers = new HashSet<>(peers);
            this.responses = new ConcurrentHashMap<>();
            this.blockHashes = new ConcurrentHashMap<>();
            this.requestTime = System.currentTimeMillis();
            this.timeoutMs = timeoutMs;
            this.minPeers = minPeers;
            this.minResponseRate = minResponseRate;
            this.consensusThreshold = consensusThreshold;
            this.consensusReached = false;
            this.consensusBlock = null;
        }

        /**
         * Check if request has timed out
         */
        public boolean isTimedOut() {
            return System.currentTimeMillis() - requestTime > timeoutMs;
        }

        /**
         * Get response rate (percentage of peers that responded)
         */
        public double getResponseRate() {
            if (requestedPeers.isEmpty()) return 0.0;
            return (double) responses.size() / requestedPeers.size();
        }
    }

    /**
     * Active block requests: requestKey -> BlockRequest
     * RequestKey format: "networkID:chainID:blockID"
     */
    private Map<String, BlockRequest> activeRequests = new ConcurrentHashMap<>();

    /**
     * Create request key from network, chain, and block IDs
     */
    public static String createRequestKey(String networkID, Long chainID, Long blockID) {
        return networkID + ":" + chainID + ":" + blockID;
    }

    /**
     * Create a new multi-peer block request with network-specific consensus configuration
     *
     * @param networkID Network identifier
     * @param chainID Chain identifier (1, 2, or 3)
     * @param blockID Block number to request
     * @param peers Set of peer IDs to request from
     * @param timeoutMs Timeout in milliseconds
     * @param minPeers Minimum number of peers required for consensus
     * @param minResponseRate Minimum response rate (0.0 to 1.0)
     * @param consensusThreshold Consensus threshold (0.0 to 1.0)
     * @return Request key for tracking
     */
    public String createRequest(String networkID, Long chainID, Long blockID, Set<String> peers,
                               long timeoutMs, int minPeers, double minResponseRate, double consensusThreshold) {
        String requestKey = createRequestKey(networkID, chainID, blockID);
        BlockRequest request = new BlockRequest(networkID, chainID, blockID, peers,
                                                timeoutMs, minPeers, minResponseRate, consensusThreshold);
        activeRequests.put(requestKey, request);

        System.out.println("[Consensus] Created block request " + requestKey + " for " + peers.size() + " peers");
        System.out.println("[Consensus]   Config: minPeers=" + minPeers +
                         ", responseRate=" + minResponseRate +
                         ", threshold=" + consensusThreshold +
                         ", timeout=" + timeoutMs + "ms");
        return requestKey;
    }

    /**
     * Record a block response from a peer
     *
     * @param requestKey Request identifier
     * @param peerID Peer that sent the response
     * @param block Block received from peer
     * @return true if response was recorded, false if request not found
     */
    public boolean recordResponse(String requestKey, String peerID, NetworkBlock block) {
        BlockRequest request = activeRequests.get(requestKey);
        if (request == null) {
            System.err.println("[Consensus] Request not found: " + requestKey);
            return false;
        }

        // Calculate block hash for comparison
        String blockHash = calculateBlockHash(block);

        request.responses.put(peerID, block);
        request.blockHashes.put(peerID, blockHash);

        System.out.println("[Consensus] Recorded response from " + peerID.substring(0, 8) +
                         " for " + requestKey +
                         " (hash: " + blockHash.substring(0, 8) + ")" +
                         " (" + request.responses.size() + "/" + request.requestedPeers.size() + ")");

        return true;
    }

    /**
     * Calculate a cryptographic hash of the block for comparison
     * Uses SHA-256 hash of: block number, chain ID, event count, and event blob hashes
     */
    private String calculateBlockHash(NetworkBlock block) {
        if (block == null) return "null";

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Hash block metadata
            digest.update(String.valueOf(block.block).getBytes("UTF-8"));
            digest.update(String.valueOf(block.chain).getBytes("UTF-8"));
            digest.update(String.valueOf(block.networkEvents != null ? block.networkEvents.size() : 0).getBytes("UTF-8"));

            // Hash event blobs in sorted order for deterministic comparison
            if (block.networkEvents != null && !block.networkEvents.isEmpty()) {
                List<Integer> sortedKeys = new ArrayList<>(block.networkEvents.keySet());
                Collections.sort(sortedKeys);

                for (Integer key : sortedKeys) {
                    byte[] eventBlob = block.networkEvents.get(key);
                    if (eventBlob != null) {
                        digest.update(eventBlob);
                    }
                }
            }

            // Convert to hex string
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (Exception e) {
            System.err.println("[Consensus] Error calculating block hash: " + e.getMessage());
            return "error";
        }
    }

    /**
     * Check if consensus has been reached for a request
     *
     * Consensus is reached when:
     * 1. Minimum number of peers responded
     * 2. Minimum response rate achieved
     * 3. Majority of responses agree on same block hash
     *
     * @param requestKey Request identifier
     * @return true if consensus reached, false otherwise
     */
    public boolean checkConsensus(String requestKey) {
        BlockRequest request = activeRequests.get(requestKey);
        if (request == null) return false;

        // Already reached consensus
        if (request.consensusReached) return true;

        int responseCount = request.responses.size();

        // Check minimum peer count (use request-specific settings)
        if (responseCount < request.minPeers) {
            System.out.println("[Consensus] Insufficient responses: " + responseCount + " < " + request.minPeers);
            return false;
        }

        // Check response rate (use request-specific settings)
        double responseRate = request.getResponseRate();
        if (responseRate < request.minResponseRate) {
            System.out.println("[Consensus] Insufficient response rate: " + responseRate + " < " + request.minResponseRate);
            return false;
        }

        // Count block hash occurrences
        Map<String, Integer> hashCounts = new HashMap<>();
        Map<String, NetworkBlock> hashToBlock = new HashMap<>();

        for (Map.Entry<String, String> entry : request.blockHashes.entrySet()) {
            String peerID = entry.getKey();
            String hash = entry.getValue();

            hashCounts.put(hash, hashCounts.getOrDefault(hash, 0) + 1);
            if (!hashToBlock.containsKey(hash)) {
                hashToBlock.put(hash, request.responses.get(peerID));
            }
        }

        // Find majority hash
        String majorityHash = null;
        int majorityCount = 0;

        for (Map.Entry<String, Integer> entry : hashCounts.entrySet()) {
            if (entry.getValue() > majorityCount) {
                majorityCount = entry.getValue();
                majorityHash = entry.getKey();
            }
        }

        // Check if majority reaches consensus threshold (use request-specific settings)
        double agreementRate = (double) majorityCount / responseCount;

        if (agreementRate >= request.consensusThreshold) {
            request.consensusReached = true;
            request.consensusBlock = hashToBlock.get(majorityHash);

            System.out.println("[Consensus] REACHED for " + requestKey);
            System.out.println("[Consensus]   Agreement: " + majorityCount + "/" + responseCount +
                             " (" + String.format("%.1f%%", agreementRate * 100) + ")");
            System.out.println("[Consensus]   Consensus hash: " + majorityHash);

            return true;
        } else {
            System.out.println("[Consensus] No consensus yet for " + requestKey +
                             " (best: " + majorityCount + "/" + responseCount +
                             " = " + String.format("%.1f%%", agreementRate * 100) + ")");
            return false;
        }
    }

    /**
     * Get the consensus block if consensus was reached
     *
     * @param requestKey Request identifier
     * @return Consensus block, or null if consensus not reached
     */
    public NetworkBlock getConsensusBlock(String requestKey) {
        BlockRequest request = activeRequests.get(requestKey);
        if (request == null || !request.consensusReached) {
            return null;
        }
        return request.consensusBlock;
    }

    /**
     * Get a request by key
     */
    public BlockRequest getRequest(String requestKey) {
        return activeRequests.get(requestKey);
    }

    /**
     * Remove a completed or timed out request
     */
    public void removeRequest(String requestKey) {
        activeRequests.remove(requestKey);
        System.out.println("[Consensus] Removed request " + requestKey);
    }

    /**
     * Clean up timed out requests
     * Should be called periodically
     */
    public void cleanupTimedOutRequests() {
        List<String> timedOut = new ArrayList<>();

        for (Map.Entry<String, BlockRequest> entry : activeRequests.entrySet()) {
            if (entry.getValue().isTimedOut()) {
                timedOut.add(entry.getKey());
            }
        }

        for (String key : timedOut) {
            System.out.println("[Consensus] Request timed out: " + key);
            activeRequests.remove(key);
        }

        if (!timedOut.isEmpty()) {
            System.out.println("[Consensus] Cleaned up " + timedOut.size() + " timed out requests");
        }
    }

    /**
     * Get number of active requests
     */
    public int getActiveRequestCount() {
        return activeRequests.size();
    }
}
