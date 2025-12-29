/*
 * Copyright (c) 2021 Christopher Willett
 * All Rights Reserved.
 */

package dev.droppinganvil.v3;

import java.io.Serializable;
import java.util.List;

public class Configuration implements Serializable {
    public String SDF_FORMAT = "S-m-H-a-EEE-F-M-y";
    public String netID;
    public String nmiPub;
    public List<String> backendSet;
    public Boolean active = true;
    public Boolean unlimitedUpload = false;

    /**
     * ID of the current official seed for this network
     * Seeds are stored in c2 (Resources chain) and locally in seeds/ directory
     * System automatically loads all resources from c2 when joining network
     * Used for versioned seed distribution and resource lookup
     */
    public String currentSeedID;

    /**
     * ID of the previous official seed (for rollback/version tracking)
     */
    public String lastSeedID;

    /**
     * Whitelist mode: When enabled, nodes must be explicitly registered via REGISTER_NODE event
     * System reads c1 (Admin) chain for REGISTER_NODE events to build approved nodes list
     * If node is not registered and whitelist mode is enabled: connection rejected
     *
     * Default: false (open network - anyone can join)
     *
     * Network Types:
     * - false = Open Network (public access)
     * - true = Whitelist/Private Network (pre-approval required)
     */
    public Boolean whitelistMode = false;

    /**
     * Whether this network's seed should be distributed in public seed lists
     * Private networks may want to distribute seeds only through secure channels
     *
     * Default: true (include in public seed distribution)
     *
     * Use Cases:
     * - true = Open/Whitelist networks (discoverable)
     * - false = Private networks (invitation-only, seeds distributed out-of-band)
     */
    public Boolean publicSeed = true;

    /**
     * Blockchain sync interval in seconds
     * Determines how often peers request chain status updates from the network
     *
     * Default: 600 (10 minutes)
     *
     * Considerations:
     * - Lower values = more frequent syncs, better consistency, higher traffic
     * - Higher values = less traffic but slower to detect missing blocks
     * - For testing: 30 seconds
     * - For production: 600 seconds (10 minutes)
     */
    public Integer syncIntervalSeconds = 600;

    /**
     * Time To Live (TTL) - Maximum number of relay hops for network events
     * Prevents infinite loops and controls broadcast scope
     *
     * Default: 16 hops
     *
     * When a peer relays an event:
     * 1. TTL is decremented by 1
     * 2. If TTL reaches 0, event is NOT relayed further (dropped)
     * 3. Prevents network flooding and relay storms
     *
     * Considerations:
     * - Lower values = smaller network reach, less traffic, faster propagation stop
     * - Higher values = larger network reach, more traffic, deeper propagation
     * - For small networks (< 10 nodes): 8-10 hops
     * - For medium networks (10-100 nodes): 12-16 hops
     * - For large networks (100+ nodes): 20+ hops
     */
    public Integer defaultTTL = 16;

}
