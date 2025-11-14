/*
 * Copyright (c) 2021 Christopher Willett
 * All Rights Reserved.
 */

package dev.droppinganvil.v3;

import dev.droppinganvil.v3.api.CXPlugin;
import dev.droppinganvil.v3.crypt.core.CryptProvider;
import dev.droppinganvil.v3.crypt.pgpainless.PainlessCryptProvider;
import dev.droppinganvil.v3.edge.NetworkRecord;
import dev.droppinganvil.v3.exceptions.UnsafeKeywordException;
import dev.droppinganvil.v3.io.IOJob;
import dev.droppinganvil.v3.io.strings.JacksonProvider;
import dev.droppinganvil.v3.io.strings.SerializationProvider;
import dev.droppinganvil.v3.network.CXNetwork;
import dev.droppinganvil.v3.network.InputBundle;
import dev.droppinganvil.v3.network.NetworkDictionary;
import dev.droppinganvil.v3.network.events.NetworkEvent;
import dev.droppinganvil.v3.network.nodemesh.Node;
import dev.droppinganvil.v3.network.nodemesh.NodeConfig;
import dev.droppinganvil.v3.network.nodemesh.NodeMesh;
import dev.droppinganvil.v3.network.nodemesh.OutputBundle;
import dev.droppinganvil.v3.network.nodemesh.PeerDirectory;
import dev.droppinganvil.v3.network.nodemesh.bridge.CXBridge;
import dev.droppinganvil.v3.resourcecore.Availability;
import dev.droppinganvil.v3.resourcecore.Resource;
import dev.droppinganvil.v3.resourcecore.ResourceType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ConnectX {
    public static Platform platform;
    public State state = State.CXConnecting;
    private static ConcurrentHashMap<String, CXNetwork> networkMap = new ConcurrentHashMap<>();
    public final CryptProvider encryptionProvider = new PainlessCryptProvider();
    private static final transient ConcurrentHashMap<String, SerializationProvider> serializationProviders = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, CXBridge> bridgeMap = new ConcurrentHashMap<>(); // Legacy - deprecated
    private static final ConcurrentHashMap<String, dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider> bridgeProviders = new ConcurrentHashMap<>();
    public final Queue<IOJob> jobQueue = new ConcurrentLinkedQueue<>();
    public static final Queue<InputBundle> eventQueue = new ConcurrentLinkedQueue<>();
    public static final Queue<OutputBundle> outputQueue = new ConcurrentLinkedQueue<>();
    public File cxRoot = new File("ConnectX");
    public File nodemesh;
    public File resources;
    private transient static CXNetwork cx;
    private transient Node self;
    private static ConcurrentHashMap<String, CXPlugin> plugins = new ConcurrentHashMap<>();
    private static transient List<String> reserved = Arrays.asList("SYSTEM", "CX", "cxJSON1", "CXNET");
    public NodeMesh nodeMesh;


    public ConnectX() throws IOException {
        this("ConnectX");
    }

    public ConnectX(String rootDir) throws IOException {
        this.cxRoot = new File(rootDir);

        String osS = System.getProperty("os.name");
        osS = osS.toLowerCase(Locale.ROOT);
        if (osS.contains("linux")) {
            platform = Platform.LINUX_GENERIC;
        } else if (osS.contains("windows")) {
            platform = Platform.WINDOWS;
        } else if (osS.contains("osx")) {
            platform = Platform.OSX;
        } else if (osS.contains("connectx")) {
            platform = Platform.ConnectX;
        }
        if (platform == null) platform = Platform.Unknown;

        serializationProviders.put("cxJSON1", new JacksonProvider());

        //Setup filesystem
        if (!cxRoot.exists()) {
            if (!cxRoot.mkdir()) throw new IOException();
        }
        nodemesh = new File(cxRoot, "nodemesh");
        if (!nodemesh.exists()) if (!nodemesh.mkdir()) throw new IOException();
        resources = new File(nodemesh, "nodemesh-resources");
        if (!resources.exists()) if (!resources.mkdir()) throw new IOException();

        //TODO network join

    }
    public static void checkSafety(String s) throws UnsafeKeywordException {
        //TODO filesystem safety
        if (reserved.contains(s)) throw new UnsafeKeywordException();
    }
    public static void checkProvider(String method) {
        if (!serializationProviders.containsKey(method)) throw new NullPointerException();
    }
    public static boolean isProviderPresent(String method) {
        return serializationProviders.containsKey(method);
    }
    public static String serialize(String method, Object o) throws Exception {
        checkProvider(method);
        return serializationProviders.get(method).getString(o);
    }
    public static void serialize(String method, Object o, OutputStream os) throws Exception {
        checkProvider(method);
        serializationProviders.get(method).writeToStream(os, o);
    }
    public static Object deserialize(String method, String s, Class<?> clazz) throws Exception {
        checkProvider(method);
        return serializationProviders.get(method).getObject(s, clazz);
    }
    public static Object deserialize(String method, InputStream is, Class<?> clazz) throws Exception {
        checkProvider(method);
        return serializationProviders.get(method).getObject(is, clazz);
    }
    public static void addSerializationProvider(String name, SerializationProvider provider) throws UnsafeKeywordException, IllegalAccessException {
        checkSafety(name);
        if (serializationProviders.containsKey(name)) throw new IllegalAccessException();
        serializationProviders.put(name, provider);
    }

    // Bridge Provider Management (similar to SerializationProvider)
    public static void addBridgeProvider(dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider provider, ConnectX instance) throws UnsafeKeywordException, IllegalAccessException {
        String protocol = provider.getBridgeProtocol();
        checkSafety(protocol);
        if (bridgeProviders.containsKey(protocol)) {
            throw new IllegalAccessException("Bridge provider " + protocol + " already registered");
        }
        provider.initialize(instance);
        bridgeProviders.put(protocol, provider);
    }

    public static dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider getBridgeProvider(String protocol) {
        return bridgeProviders.get(protocol);
    }

    public static boolean isBridgeProviderPresent(String protocol) {
        return bridgeProviders.containsKey(protocol);
    }
    // Instance methods for signing (use these for proper per-instance crypto)
    public Object getSignedObject(String cxID, InputStream is, Class<?> clazz, String method) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (encryptionProvider.verifyAndStrip(is, baos, cxID)) {
            return deserialize(method, baos.toString("UTF-8"), clazz);
        }
        return null;
    }

    public ByteArrayOutputStream signObject(Object o, Class<?> clazz, String method) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String s = serialize(method, o);
        ByteArrayInputStream bais = new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        encryptionProvider.sign(bais, baos);
        bais.close();
        return baos;
    }

    // Static methods kept for backward compatibility (deprecated - use instance methods)
    @Deprecated
    public static Object getSignedObject(ConnectX cx, String cxID, InputStream is, Class<?> clazz, String method) throws Exception {
        return cx.getSignedObject(cxID, is, clazz, method);
    }

    @Deprecated
    public static ByteArrayOutputStream signObject(ConnectX cx, Object o, Class<?> clazz, String method) throws Exception {
        return cx.signObject(o, clazz, method);
    }
    public String getOwnID() {
        return self != null ? self.cxID : null;
    }

    public void setSelf(Node selfNode) {
        self = selfNode;
    }

    public Node getSelf() {
        return self;
    }

    /**
     * Attempt to bootstrap into CXNET if not already loaded
     * This method:
     * 1. Checks if CXNET network already exists
     * 2. If not, tries to load from local seeds/ directory
     * 3. If no local seed, queues SEED_REQUEST to known bootstrap nodes (EPOCH)
     */
    private void attemptCXNETBootstrap() {
        try {
            // Check if CXNET already exists
            if (networkMap.containsKey("CXNET")) {
                System.out.println("[Bootstrap] CXNET already loaded");
                return;
            }

            System.out.println("[Bootstrap] CXNET not found, attempting bootstrap...");

            // Check for local seeds directory
            File seedsDir = new File(cxRoot, "seeds");
            if (!seedsDir.exists()) {
                seedsDir.mkdirs();
                System.out.println("[Bootstrap] Created seeds/ directory");
            }

            // Look for existing seed files
            File[] seedFiles = seedsDir.listFiles((dir, name) -> name.endsWith(".cxn"));
            if (seedFiles != null && seedFiles.length > 0) {
                // Load the most recently modified seed
                File latestSeed = seedFiles[0];
                for (File f : seedFiles) {
                    if (f.lastModified() > latestSeed.lastModified()) {
                        latestSeed = f;
                    }
                }

                System.out.println("[Bootstrap] Loading local seed: " + latestSeed.getName());
                dev.droppinganvil.v3.network.Seed seed = dev.droppinganvil.v3.network.Seed.load(latestSeed);

                // Apply seed (loads networks, peers, certificates)
                applySeed(seed);

                System.out.println("[Bootstrap] Successfully bootstrapped from local seed");
                return;
            }

            // No local seed found - request from EPOCH (if we're not EPOCH)
            if (self != null && "00000000-0000-0000-0000-000000000001".equals(self.cxID)) {
                System.out.println("[Bootstrap] This is EPOCH - no bootstrap needed");
                return;
            }

            System.out.println("[Bootstrap] No local seed found, requesting from EPOCH...");
            requestSeedFromEpoch();

        } catch (Exception e) {
            System.err.println("[Bootstrap] Bootstrap attempt failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Apply a seed to this ConnectX instance
     * Loads networks, adds peers to directory, and caches certificates
     * @param seed Seed to apply
     * @throws Exception if application fails
     */
    private void applySeed(dev.droppinganvil.v3.network.Seed seed) throws Exception {
        System.out.println("[Seed] Applying seed " + seed.seedID);
        System.out.println("[Seed]   Networks: " + seed.networks.size());
        System.out.println("[Seed]   HV Peers: " + seed.hvPeers.size());
        System.out.println("[Seed]   Certificates: " + seed.certificates.size());

        // Add hv peers to directory
        for (dev.droppinganvil.v3.network.nodemesh.Node peer : seed.hvPeers) {
            PeerDirectory.addNode(peer);
            System.out.println("[Seed] Added peer: " + peer.cxID);
        }

        // Import networks
        for (dev.droppinganvil.v3.network.CXNetwork network : seed.networks) {
            String networkID = network.configuration.netID;
            networkMap.put(networkID, network);
            System.out.println("[Seed] Loaded network: " + networkID);
        }

        // Cache certificates
        for (java.util.Map.Entry<String, String> cert : seed.certificates.entrySet()) {
            try {
                encryptionProvider.cacheCert(cert.getKey(), false, false);
                System.out.println("[Seed] Cached certificate: " + cert.getKey());
            } catch (Exception e) {
                System.err.println("[Seed] Failed to cache certificate for " + cert.getKey() + ": " + e.getMessage());
            }
        }

        System.out.println("[Seed] Seed application complete");
    }

    /**
     * Request CXNET seed from EPOCH NMI via SEED_REQUEST event
     * Queues a SEED_REQUEST to be sent to EPOCH
     */
    private void requestSeedFromEpoch() {
        try {
            // Create SEED_REQUEST event
            dev.droppinganvil.v3.network.events.NetworkEvent seedRequest =
                new dev.droppinganvil.v3.network.events.NetworkEvent(
                    dev.droppinganvil.v3.network.events.EventType.SEED_REQUEST,
                    "CXNET".getBytes("UTF-8"));
            seedRequest.eT = dev.droppinganvil.v3.network.events.EventType.SEED_REQUEST.name();
            seedRequest.iD = java.util.UUID.randomUUID().toString();

            // Set path to EPOCH NMI
            dev.droppinganvil.v3.network.CXPath epochPath = new dev.droppinganvil.v3.network.CXPath();
            epochPath.cxID = "00000000-0000-0000-0000-000000000001"; // EPOCH UUID
            epochPath.scope = "CXN";
            epochPath.network = "CXNET";
            seedRequest.p = epochPath;

            // Create container
            dev.droppinganvil.v3.network.events.NetworkContainer seedRequestContainer =
                new dev.droppinganvil.v3.network.events.NetworkContainer();
            seedRequestContainer.se = "cxJSON1";
            seedRequestContainer.s = false;

            // Queue the request (will be transmitted by OutConnectionController)
            OutputBundle seedRequestBundle = new OutputBundle(seedRequest, null, null, null, seedRequestContainer);
            synchronized (outputQueue) {
                outputQueue.add(seedRequestBundle);
            }

            System.out.println("[Bootstrap] SEED_REQUEST queued for EPOCH");
            System.out.println("[Bootstrap] Waiting for SEED_RESPONSE...");

        } catch (Exception e) {
            System.err.println("[Bootstrap] Failed to request seed from EPOCH: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initialize and start the P2P network layer
     * Automatically attempts to bootstrap CXNET if not already loaded
     * @param port Port number for incoming connections
     * @throws IOException if network initialization fails
     */
    public void connect(int port) throws IOException {
        dev.droppinganvil.v3.network.nodemesh.OutConnectionController outController =
            new dev.droppinganvil.v3.network.nodemesh.OutConnectionController(this);
        nodeMesh = dev.droppinganvil.v3.network.nodemesh.NodeMesh.initializeNetwork(this, port, outController);

        // Attempt automatic CXNET bootstrap after network layer is ready
        attemptCXNETBootstrap();
    }

    public void connect() throws IOException {
        connect(49152); // Default P2P port
    }

    /**
     * Create a new CXNetwork with this node as Network Master Identity (NMI)
     * @param networkID Unique network identifier (NOT "CXNET" - reserved for global network)
     * @return The created CXNetwork
     * @throws IllegalAccessException if networkID is reserved or node is not initialized
     */
    public CXNetwork createNetwork(String networkID) throws IllegalAccessException {
        // TODO: Re-enable CXNET reservation after EPOCH NMI initialization
        // if (networkID.equalsIgnoreCase("CXNET")) {
        //     throw new IllegalAccessException("CXNET is reserved for the global network");
        // }
        if (self == null || self.cxID == null) {
            throw new IllegalAccessException("Node must be initialized before creating network");
        }
        if (encryptionProvider.getPublicKey() == null) {
            throw new IllegalAccessException("Encryption provider must be initialized before creating network");
        }

        // Create network object
        CXNetwork network = new CXNetwork();
        network.networkState = State.ConnectNetworks;

        // Setup Configuration
        network.configuration = new Configuration();
        network.configuration.netID = networkID;
        network.configuration.nmiPub = encryptionProvider.getPublicKey();
        network.configuration.backendSet = new java.util.ArrayList<>();
        network.configuration.backendSet.add(self.cxID); // NMI is first backend
        network.configuration.active = true;

        // Setup NetworkDictionary
        network.networkDictionary = new NetworkDictionary();
        network.networkDictionary.nmi = encryptionProvider.getPublicKey();
        network.networkDictionary.networkID = networkID;
        network.networkDictionary.networkCreate = System.currentTimeMillis();
        network.networkDictionary.lastUpdate = System.currentTimeMillis();

        // Assign chain IDs
        network.networkDictionary.c1 = 1L; // Administrative chain
        network.networkDictionary.c2 = 2L; // Resources chain
        network.networkDictionary.c3 = 3L; // Events chain
        network.networkDictionary.backendSet.add(self.cxID);

        // Initialize blockchain chains with genesis blocks
        network.c1 = new dev.droppinganvil.v3.edge.NetworkRecord(networkID, 1L);
        network.c1.networkID = networkID;
        network.c1.chainID = 1L;
        network.c1.current = new dev.droppinganvil.v3.edge.NetworkBlock(0L);
        network.c1.blockMap.put(0L, network.c1.current);

        network.c2 = new dev.droppinganvil.v3.edge.NetworkRecord(networkID, 2L);
        network.c2.networkID = networkID;
        network.c2.chainID = 2L;
        network.c2.current = new dev.droppinganvil.v3.edge.NetworkBlock(0L);
        network.c2.blockMap.put(0L, network.c2.current);

        network.c3 = new dev.droppinganvil.v3.edge.NetworkRecord(networkID, 3L);
        network.c3.networkID = networkID;
        network.c3.chainID = 3L;
        network.c3.current = new dev.droppinganvil.v3.edge.NetworkBlock(0L);
        network.c3.blockMap.put(0L, network.c3.current);

        // Setup network permissions
        network.networkPermissions = new us.anvildevelopment.util.tools.permissions.BasicPermissionContainer();

        // Initialize NMI permissions directly (bootstrap problem - no prior authority)
        java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> nmiPermissions = new java.util.HashMap<>();

        // Grant NMI full network-level permissions with high weight (100)
        nmiPermissions.put(Permission.AddAccount.name(),
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.AddAccount.name(), true, 100));
        nmiPermissions.put(Permission.NetworkUpload.name(),
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.NetworkUpload.name(), true, 100));
        nmiPermissions.put(Permission.UploadGlobalResource.name(),
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.UploadGlobalResource.name(), true, 100));

        // Grant NMI chain-specific permissions (Record-{chainID} format)
        nmiPermissions.put(Permission.Record.name() + "-" + network.networkDictionary.c1,
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.Record.name() + "-" + network.networkDictionary.c1, true, 100));
        nmiPermissions.put(Permission.Record.name() + "-" + network.networkDictionary.c2,
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.Record.name() + "-" + network.networkDictionary.c2, true, 100));
        nmiPermissions.put(Permission.Record.name() + "-" + network.networkDictionary.c3,
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.Record.name() + "-" + network.networkDictionary.c3, true, 100));

        // Grant permission management capabilities
        nmiPermissions.put(us.anvildevelopment.util.tools.permissions.Actions.ADD_ENTRY.name(),
            new us.anvildevelopment.util.tools.permissions.BasicEntry(us.anvildevelopment.util.tools.permissions.Actions.ADD_ENTRY.name(), true, 100));
        nmiPermissions.put(us.anvildevelopment.util.tools.permissions.Actions.EDIT_ENTRY.name(),
            new us.anvildevelopment.util.tools.permissions.BasicEntry(us.anvildevelopment.util.tools.permissions.Actions.EDIT_ENTRY.name(), true, 100));

        network.networkPermissions.permissionSet.put(self.cxID, nmiPermissions);

        // Add lower-privilege default permissions for joining nodes (weight 10)
        // These allow nodes to add themselves and record to events chain
        java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> defaultPermissions = new java.util.HashMap<>();
        defaultPermissions.put(Permission.AddAccount.name(),
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.AddAccount.name(), true, 10));
        defaultPermissions.put(Permission.Record.name() + "-" + network.networkDictionary.c3,
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.Record.name() + "-" + network.networkDictionary.c3, true, 10));

        // Store default permissions template (used when nodes join)
        network.networkPermissions.permissionSet.put("DEFAULT_NODE", defaultPermissions);

        // Copy permissions to NetworkDictionary
        network.networkDictionary.networkPermissions = network.networkPermissions;

        // Add network to global map
        networkMap.put(networkID, network);

        return network;
    }

    /**
     * Export a network configuration to a signed file using NetworkContainer pattern
     * @param networkID Network to export
     * @param exportFile File to write the signed network data to
     * @throws Exception if network doesn't exist, signing fails, or IO error occurs
     */
    public void exportNetwork(String networkID, File exportFile) throws Exception {
        CXNetwork network = networkMap.get(networkID);
        if (network == null) {
            throw new IllegalArgumentException("Network " + networkID + " not found");
        }

        // Follow the same pattern as OutConnectionController.transmitEvent()
        dev.droppinganvil.v3.network.events.NetworkContainer nc = new dev.droppinganvil.v3.network.events.NetworkContainer();
        nc.se = "cxJSON1";
        nc.s = false; // Not E2E encrypted
        nc.iD = getOwnID(); // Sender's cxID
        if (NodeConfig.revealVersion) nc.v = NodeConfig.cxV;

        // Sign the inner CXNetwork object
        byte[] signedNetwork = signObject(network, CXNetwork.class, nc.se).toByteArray();
        nc.e = signedNetwork;

        // Sign the outer NetworkContainer
        byte[] signedContainer = signObject(nc, dev.droppinganvil.v3.network.events.NetworkContainer.class, nc.se).toByteArray();

        // Write to file
        FileOutputStream fos = new FileOutputStream(exportFile);
        fos.write(signedContainer);
        fos.close();
    }

    /**
     * Import a network configuration from a signed file using NetworkContainer pattern
     * Follows same pattern as NodeMesh.processNetworkInput() with both encryption layers
     * @param importFile File containing signed network data
     * @return The imported CXNetwork
     * @throws Exception if verification fails, deserialization fails, or IO error occurs
     */
    public CXNetwork importNetwork(File importFile) throws Exception {
        // Follow the same pattern as NodeMesh.processNetworkInput()
        FileInputStream fis = new FileInputStream(importFile);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Step 1: Decrypt outer NetworkContainer (verifies outer signature)
        Object o = encryptionProvider.decrypt(fis, baos);
        String networkContainer = baos.toString("UTF-8");

        // Step 2: Deserialize NetworkContainer from decrypted JSON
        dev.droppinganvil.v3.network.events.NetworkContainer nc =
            (dev.droppinganvil.v3.network.events.NetworkContainer) deserialize(
                "cxJSON1", networkContainer, dev.droppinganvil.v3.network.events.NetworkContainer.class);

        if (nc == null || nc.iD == null) {
            throw new IllegalAccessException("Invalid network container - missing sender ID");
        }

        // Step 3: Verify and extract inner CXNetwork using sender's cxID from nc.iD
        ByteArrayInputStream bais = new ByteArrayInputStream(nc.e);
        ByteArrayOutputStream networkBaos = new ByteArrayOutputStream();

        boolean verified = encryptionProvider.verifyAndStrip(bais, networkBaos, nc.iD);
        if (!verified) {
            throw new IllegalAccessException("Network verification failed - invalid signature from " + nc.iD);
        }

        // Step 4: Deserialize the inner CXNetwork
        CXNetwork network = (CXNetwork) deserialize(nc.se, networkBaos.toString("UTF-8"), CXNetwork.class);

        if (network == null) {
            throw new IllegalAccessException("Network deserialization failed");
        }

        // Verify network has required components
        if (network.configuration == null || network.networkDictionary == null) {
            throw new IllegalStateException("Invalid network structure - missing required components");
        }

        String networkID = network.configuration.netID;
        if (networkID == null || networkID.isEmpty()) {
            throw new IllegalStateException("Invalid network - missing network ID");
        }

        // Add to network map
        networkMap.put(networkID, network);

        fis.close();
        baos.close();
        bais.close();
        networkBaos.close();

        return network;
    }

    /**
     * Grant permissions to a node to join this network
     * Call this after importing a network to allow self to participate
     * @param networkID Network to join
     * @throws IllegalAccessException if network doesn't exist or permissions can't be granted
     */
    public void joinNetwork(String networkID) throws IllegalAccessException {
        CXNetwork network = networkMap.get(networkID);
        if (network == null) {
            throw new IllegalArgumentException("Network " + networkID + " not found - import it first");
        }
        if (self == null || self.cxID == null) {
            throw new IllegalAccessException("Node must be initialized before joining network");
        }

        // Copy default permissions template for this node
        if (network.networkPermissions.permissionSet.containsKey("DEFAULT_NODE")) {
            java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> defaultPerms =
                network.networkPermissions.permissionSet.get("DEFAULT_NODE");

            // Create new permission map for this node
            java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> nodePerms = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, us.anvildevelopment.util.tools.permissions.Entry> entry : defaultPerms.entrySet()) {
                us.anvildevelopment.util.tools.permissions.Entry e = entry.getValue();
                nodePerms.put(e.getName(),
                    new us.anvildevelopment.util.tools.permissions.BasicEntry(e.getName(), e.getAllow(), e.getWeight()));
            }

            network.networkPermissions.permissionSet.put(self.cxID, nodePerms);
        } else {
            throw new IllegalAccessException("Network does not have default permissions template");
        }
    }

    public static boolean checkGlobalPermission(String cxID, String permission) {
        assert cx != null;
        assert !cxID.contains("SYSTEM");
        return cx.checkNetworkPermission(cxID, permission);
    }
    public Resource locateResource(String networkID, ResourceType type, Availability availability) {
        //TODO implement resource location
        return null;
    }

    public File locateResourceDIR(Resource r) {
        if (r.p == null) return null;
        File f = null;
        switch (r.p.getScope()) {
            case CXN:
                f = new File(resources, r.p.network);
                break;
            case CXS:
                f = new File(resources, r.p.cxID);
                break;
        }
        return f;
    }

    @Deprecated
    public static File locateResourceDIR(ConnectX cx, Resource r) {
        return cx.locateResourceDIR(r);
    }
    public static boolean addPlugin(CXPlugin cxp) {
        try {
            checkSafety(cxp.serviceName);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (plugins.containsKey(cxp.serviceName)) return false;
        plugins.put(cxp.serviceName, cxp);
        return true;
    }
    public static boolean sendPluginEvent(NetworkEvent ne, String eventType) {
        if (!plugins.containsKey(eventType)) return false;
        return plugins.get(eventType).handleEvent(ne);
    }
    /**
     * Record a NetworkEvent to the blockchain
     * @param ne NetworkEvent to record
     * @param senderID cxID of the node attempting to record (for permission check)
     * @return true if successfully recorded, false otherwise
     */
    public boolean recordEvent(NetworkEvent ne, String senderID) {
        if (ne == null || ne.p == null) {
            return false;
        }

        // Get network ID from CXPath
        String networkID = ne.p.network;
        if (networkID == null || networkID.isEmpty()) {
            return false;
        }

        // Get the network
        CXNetwork network = networkMap.get(networkID);
        if (network == null) {
            return false;
        }

        // Determine which chain to use based on event type
        // Default to c3 (Events chain) for most events
        NetworkRecord targetChain = network.c3;
        Long chainID = network.networkDictionary.c3;

        // TODO: Add logic to route specific event types to c1 (Admin) or c2 (Resources)
        // For now, all events go to c3

        // Check if sender has permission to record to this chain
        if (!network.checkChainPermission(senderID, Permission.Record.name(), chainID)) {
            // Permission denied
            return false;
        }

        // Synchronize on the chain to prevent concurrent modification
        synchronized (targetChain) {
            // Get current block
            dev.droppinganvil.v3.edge.NetworkBlock currentBlock = targetChain.current;
            if (currentBlock == null) {
                // Create genesis block if none exists
                currentBlock = new dev.droppinganvil.v3.edge.NetworkBlock(0L);
                targetChain.current = currentBlock;
                targetChain.blockMap.put(0L, currentBlock);
            }

            // Check if current block is full
            if (currentBlock.networkEvents.size() >= targetChain.blockLength) {
                // Create new block
                Long newBlockID = currentBlock.block + 1;
                dev.droppinganvil.v3.edge.NetworkBlock newBlock = new dev.droppinganvil.v3.edge.NetworkBlock(newBlockID);

                // Add to block map
                targetChain.blockMap.put(newBlockID, newBlock);

                // Update current block reference
                targetChain.current = newBlock;
                currentBlock = newBlock;
            }

            // Add event to current block
            int eventIndex = currentBlock.networkEvents.size();
            currentBlock.networkEvents.put(eventIndex, ne);
        }

        return true;
    }

    /**
     * @deprecated Use instance method recordEvent(NetworkEvent, String) instead
     */
    @Deprecated
    public static boolean recordEvent(NetworkEvent ne) {
        // Legacy static method - cannot determine sender or instance
        // Return false to indicate recording not performed
        return false;
    }

    public static CXNetwork getNetwork(String networkID) {
        if (!networkMap.containsKey(networkID)) return null;
        return networkMap.get(networkID);
    }

    public void loadLan() {
        File lan = new File(cxRoot, "lan");
        if (!lan.exists()) lan.mkdir();
        File[] files = lan.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().contains(".cxi")) {
                try {
                    Node n = (Node) getSignedObject(getOwnID(), f.toURL().openStream(), Node.class, "cxJSON1");
                    PeerDirectory.lan.put(n.cxID, n);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static boolean isPeerLoaded(String cxID) {
        return PeerDirectory.peerCache.containsKey(cxID);
    }

    public static void loadPeer(String cxID, boolean sync, boolean lookups) {
        //TODO implement peer loading
        try {
            PeerDirectory.lookup(cxID, lookups, sync);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean connectBridge(CXBridge cxBridge) {
        if (bridgeMap.containsKey(cxBridge.getProtocol())) return false;
        bridgeMap.put(cxBridge.getProtocol(), cxBridge);
        return true;
    }


}
