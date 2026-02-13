/*
 * Copyright (c) 2025. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.edge.NetworkBlock;
import dev.droppinganvil.v3.edge.NetworkRecord;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Blockchain Persistence Layer
 *
 * Efficient block-per-file architecture for scalable blockchain storage.
 *
 * Architecture:
 * - Chain metadata (NetworkRecord) stored as lightweight JSON (just pointers + config)
 * - Individual blocks stored as separate files for on-demand loading
 * - Blocks only loaded into memory when needed (lazy loading)
 * - Thread-safe with per-chain locks
 * - Supports up to 100 chains per network
 *
 * File Structure:
 * {cxRoot}/
 *   └── blockchain/
 *       └── {networkID}/
 *           ├── chain-1.json           (c1 metadata - lightweight)
 *           ├── chain-2.json           (c2 metadata - lightweight)
 *           ├── chain-3.json           (c3 metadata - lightweight)
 *           ├── ...
 *           ├── chain-100.json         (up to 100 chains)
 *           └── blocks/
 *               ├── chain-1/
 *               │   ├── block-0.json   (individual block files)
 *               │   ├── block-1.json
 *               │   └── block-2.json
 *               ├── chain-2/
 *               │   └── block-0.json
 *               └── chain-3/
 *                   ├── block-0.json
 *                   └── block-1.json
 *
 * Usage:
 * - Save new block: persistence.saveBlock(networkID, chainID, block)
 * - Save chain metadata: persistence.saveChainMetadata(chain)
 * - Load on startup: persistence.loadChain(networkID, chainID)
 * - Blocks loaded lazily from disk when accessed
 */
public class BlockchainPersistence {

    public static final int MAX_CHAINS = 100;

    private final File cxRoot;

    // Per-chain locks to prevent concurrent writes
    private final ReentrantLock[] chainLocks = new ReentrantLock[MAX_CHAINS + 1]; // Index 0-100

    /**
     * Create persistence handler for a ConnectX instance
     * @param cxRoot Root directory for this ConnectX instance
     */
    public BlockchainPersistence(File cxRoot) {
        this.cxRoot = cxRoot;
        // Initialize chain locks
        for (int i = 0; i < chainLocks.length; i++) {
            chainLocks[i] = new ReentrantLock();
        }
    }

    /**
     * Save a single block to disk
     *
     * @param networkID Network identifier
     * @param chainID Chain identifier (1-100)
     * @param block The block to save
     * @throws Exception if serialization or I/O fails
     */
    public void saveBlock(String networkID, Long chainID, NetworkBlock block) throws Exception {
        if (networkID == null || chainID == null || block == null || block.block == null) {
            throw new IllegalArgumentException("networkID, chainID, and block must not be null");
        }

        validateChainID(chainID);

        chainLocks[chainID.intValue()].lock();
        try {
            // Ensure blocks directory exists
            File blocksDir = getBlocksDir(networkID, chainID);
            if (!blocksDir.exists()) {
                blocksDir.mkdirs();
            }

            // Serialize block to JSON
            String json = ConnectX.serialize("cxJSON1", block);

            // Write to file
            File blockFile = new File(blocksDir, "block-" + block.block + ".json");
            try (FileWriter writer = new FileWriter(blockFile)) {
                writer.write(json);
                writer.flush();
            }

            // System.out.println("[Blockchain Persistence] Saved block " + block.block +
            //                  " for chain " + chainID + " (" + block.networkEvents.size() + " events)");

        } finally {
            chainLocks[chainID.intValue()].unlock();
        }
    }

    /**
     * Load a single block from disk
     *
     * @param networkID Network identifier
     * @param chainID Chain identifier (1-100)
     * @param blockID Block identifier
     * @return Loaded NetworkBlock, or null if file doesn't exist
     * @throws Exception if deserialization or I/O fails
     */
    public NetworkBlock loadBlock(String networkID, Long chainID, Long blockID) throws Exception {
        if (networkID == null || chainID == null || blockID == null) {
            throw new IllegalArgumentException("networkID, chainID, and blockID must not be null");
        }

        validateChainID(chainID);

        File blockFile = new File(getBlocksDir(networkID, chainID), "block-" + blockID + ".json");
        if (!blockFile.exists()) {
            return null;
        }

        chainLocks[chainID.intValue()].lock();
        try {
            // Read file content
            StringBuilder json = new StringBuilder();
            try (FileReader reader = new FileReader(blockFile)) {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    json.append(buffer, 0, read);
                }
            }

            // Deserialize from JSON
            return (NetworkBlock) ConnectX.deserialize("cxJSON1", json.toString(), NetworkBlock.class);

        } finally {
            chainLocks[chainID.intValue()].unlock();
        }
    }

    /**
     * Save chain metadata (lightweight - just pointers and config, no blocks)
     *
     * @param chain The chain to save
     * @param networkID Network identifier (needed for file path)
     * @throws Exception if serialization or I/O fails
     */
    public void saveChainMetadata(NetworkRecord chain, String networkID) throws Exception {
        if (chain == null || networkID == null) {
            throw new IllegalArgumentException("Chain and networkID must not be null");
        }

        if (chain.chainID == null) {
            throw new IllegalArgumentException("Chain must have a chainID");
        }

        validateChainID(chain.chainID);

        chainLocks[chain.chainID.intValue()].lock();
        try {
            // Ensure blockchain directory exists
            File blockchainDir = new File(cxRoot, "blockchain" + File.separator + networkID);
            if (!blockchainDir.exists()) {
                blockchainDir.mkdirs();
            }

            // Create a lightweight version of the chain for serialization
            // We only serialize metadata, not the full blockMap (blocks are stored separately)
            ChainMetadata metadata = new ChainMetadata();
            metadata.networkID = chain.networkID;
            metadata.chainID = chain.chainID;
            metadata.blockLength = chain.blockLength;
            metadata.lock = chain.lock;
            metadata.currentBlockID = (chain.current != null) ? chain.current.block : null;
            metadata.blockCount = chain.blockMap.size();

            // Serialize metadata to JSON
            String json = ConnectX.serialize("cxJSON1", metadata);

            // Write to file
            File chainFile = new File(blockchainDir, "chain-" + chain.chainID + ".json");
            try (FileWriter writer = new FileWriter(chainFile)) {
                writer.write(json);
                writer.flush();
            }

            System.out.println("[Blockchain Persistence] Saved chain " + chain.chainID + " metadata for network " +
                             networkID + " (" + metadata.blockCount + " blocks)");

        } finally {
            chainLocks[chain.chainID.intValue()].unlock();
        }
    }

    /**
     * Load chain metadata and restore block pointers
     *
     * @param networkID Network identifier
     * @param chainID Chain identifier (1-100)
     * @param loadAllBlocks If true, load all blocks into memory; if false, load on-demand
     * @return Loaded NetworkRecord with blocks loaded or ready for lazy loading
     * @throws Exception if deserialization or I/O fails
     */
    public NetworkRecord loadChain(String networkID, Long chainID, boolean loadAllBlocks) throws Exception {
        if (networkID == null || chainID == null) {
            throw new IllegalArgumentException("networkID and chainID must not be null");
        }

        validateChainID(chainID);

        File chainFile = new File(cxRoot, "blockchain" + File.separator + networkID + File.separator + "chain-" + chainID + ".json");
        if (!chainFile.exists()) {
            System.out.println("[Blockchain Persistence] No saved chain metadata found: " + chainFile.getPath());
            return null;
        }

        chainLocks[chainID.intValue()].lock();
        try {
            // Read metadata file
            StringBuilder json = new StringBuilder();
            try (FileReader reader = new FileReader(chainFile)) {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    json.append(buffer, 0, read);
                }
            }

            // Deserialize metadata
            ChainMetadata metadata = (ChainMetadata) ConnectX.deserialize("cxJSON1", json.toString(), ChainMetadata.class);

            // Create NetworkRecord from metadata
            NetworkRecord chain = new NetworkRecord(metadata.networkID, metadata.chainID);
            chain.blockLength = metadata.blockLength;
            chain.lock = metadata.lock;
            chain.blockMap = new ConcurrentHashMap<>();

            if (loadAllBlocks) {
                // Load all blocks into memory
                File blocksDir = getBlocksDir(networkID, chainID);
                if (blocksDir.exists()) {
                    File[] blockFiles = blocksDir.listFiles((dir, name) -> name.startsWith("block-") && name.endsWith(".json"));
                    if (blockFiles != null) {
                        for (File blockFile : blockFiles) {
                            // Extract block ID from filename: "block-X.json"
                            String filename = blockFile.getName();
                            Long blockID = Long.parseLong(filename.substring(6, filename.length() - 5));

                            NetworkBlock block = loadBlock(networkID, chainID, blockID);
                            if (block != null) {
                                chain.blockMap.put(blockID, block);
                            }
                        }
                    }
                }
            } else {
                // Lazy loading - just populate blockMap with references
                // Blocks will be loaded from disk when accessed
                // For now, we'll at least load the current block
                if (metadata.currentBlockID != null) {
                    NetworkBlock currentBlock = loadBlock(networkID, chainID, metadata.currentBlockID);
                    if (currentBlock != null) {
                        chain.blockMap.put(metadata.currentBlockID, currentBlock);
                    }
                }
            }

            // Restore current block pointer
            if (metadata.currentBlockID != null && chain.blockMap.containsKey(metadata.currentBlockID)) {
                chain.current = chain.blockMap.get(metadata.currentBlockID);
            }

            System.out.println("[Blockchain Persistence] Loaded chain " + chainID + " for network " +
                             networkID + " (" + chain.blockMap.size() + "/" + metadata.blockCount + " blocks in memory)");

            return chain;

        } finally {
            chainLocks[chainID.intValue()].unlock();
        }
    }

    /**
     * Save all blocks for a specific chain
     *
     * @param chain The chain to save
     * @param networkID Network identifier
     * @throws Exception if any save operation fails
     */
    public void saveAllBlocks(NetworkRecord chain, String networkID) throws Exception {
        if (chain == null || chain.blockMap == null) {
            return;
        }

        for (NetworkBlock block : chain.blockMap.values()) {
            saveBlock(networkID, chain.chainID, block);
        }
    }

    /**
     * Delete all blockchain data for a network
     *
     * @param networkID Network identifier
     * @throws IOException if deletion fails
     */
    public void deleteNetwork(String networkID) throws IOException {
        File networkDir = new File(cxRoot, "blockchain" + File.separator + networkID);
        deleteRecursively(networkDir);
        System.out.println("[Blockchain Persistence] Deleted blockchain data for network " + networkID);
    }

    /**
     * Check if blockchain data exists for a network
     *
     * @param networkID Network identifier
     * @return true if any chain data exists
     */
    public boolean exists(String networkID) {
        File networkDir = new File(cxRoot, "blockchain" + File.separator + networkID);
        return networkDir.exists() && networkDir.isDirectory();
    }

    /**
     * Check if a specific chain exists
     *
     * @param networkID Network identifier
     * @param chainID Chain identifier
     * @return true if chain metadata file exists
     */
    public boolean chainExists(String networkID, Long chainID) {
        File chainFile = new File(cxRoot, "blockchain" + File.separator + networkID + File.separator + "chain-" + chainID + ".json");
        return chainFile.exists();
    }

    /**
     * Get the blocks directory for a specific chain
     */
    private File getBlocksDir(String networkID, Long chainID) {
        return new File(cxRoot, "blockchain" + File.separator + networkID +
                       File.separator + "blocks" + File.separator + "chain-" + chainID);
    }

    /**
     * Validate chain ID is within acceptable range
     */
    private void validateChainID(Long chainID) {
        if (chainID < 1 || chainID > MAX_CHAINS) {
            throw new IllegalArgumentException("chainID must be between 1 and " + MAX_CHAINS);
        }
    }

    /**
     * Recursively delete directory and all contents
     */
    private void deleteRecursively(File file) throws IOException {
        if (file.exists()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) {
                    for (File child : files) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.delete(file.toPath());
        }
    }

    /**
     * Lightweight chain metadata for efficient serialization
     * (Full blocks are stored separately)
     */
    public static class ChainMetadata {
        public String networkID;
        public Long chainID;
        public Integer blockLength = 100;
        public boolean lock = false;
        public Long currentBlockID;  // Pointer to current block (not the block itself)
        public int blockCount;        // Total number of blocks

        // Default constructor for Jackson
        public ChainMetadata() {
        }
    }
}