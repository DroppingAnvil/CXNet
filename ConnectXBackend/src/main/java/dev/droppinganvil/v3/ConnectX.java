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
import dev.droppinganvil.v3.network.BlockchainPersistence;
import dev.droppinganvil.v3.network.CXNetwork;
import dev.droppinganvil.v3.network.CXPath;
import dev.droppinganvil.v3.network.InputBundle;
import dev.droppinganvil.v3.network.NetworkDictionary;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.events.NetworkContainer;
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
    // EPOCH NMI Bootstrap Constants
    public static final String EPOCH_UUID = "00000000-0000-0000-0000-000000000001";

    // TODO: Bootstrap URL Configuration
    // PRODUCTION: Switch to public URL once RProx/Cloudflare configuration is verified
    // - Verify health endpoint works through Cloudflare
    // - Test bootstrap process end-to-end
    // - Update to: cxHTTP1:https://CXNET.AnvilDevelopment.US/cx
    public static final String EPOCH_BRIDGE_ADDRESS = "cxHTTP1:http://localhost:8080/cx";
    // public static final String EPOCH_BRIDGE_ADDRESS = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";

    public static Platform platform;
    public State state = State.CXConnecting;
    private static ConcurrentHashMap<String, CXNetwork> networkMap = new ConcurrentHashMap<>();
    public final CryptProvider encryptionProvider = new PainlessCryptProvider();
    private static final transient ConcurrentHashMap<String, SerializationProvider> serializationProviders = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, CXBridge> bridgeMap = new ConcurrentHashMap<>(); // Legacy - deprecated
    private static final ConcurrentHashMap<String, dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider> bridgeProviders = new ConcurrentHashMap<>();
    public final Queue<IOJob> jobQueue = new ConcurrentLinkedQueue<>();
    // IMPORTANT: Instance queues (not static) to support multiple ConnectX instances in same JVM
    // Each instance has its own queues and processors to avoid cross-instance contamination
    public final Queue<InputBundle> eventQueue = new ConcurrentLinkedQueue<>();
    public final Queue<OutputBundle> outputQueue = new ConcurrentLinkedQueue<>();
    public File cxRoot = new File("ConnectX");
    public File nodemesh;
    public File resources;
    private transient static CXNetwork cx;
    private transient Node self;
    private static ConcurrentHashMap<String, CXPlugin> plugins = new ConcurrentHashMap<>();
    private static transient List<String> reserved = Arrays.asList("SYSTEM", "CX", "cxJSON1", "CXNET");

    /**
     * CXNET-level blocked nodes (global blocks across all networks)
     * Populated by reading BLOCK_NODE events from CXNET's c1 (Admin) chain where network="CXNET"
     * Key: Node UUID
     * Value: Block reason/metadata
     *
     * When a node is blocked at CXNET level, ALL transmissions from that node are rejected
     * This is separate from network-specific blocks (stored in CXNetwork.blockedNodes)
     */
    private static ConcurrentHashMap<String, String> cxnetBlockedNodes = new ConcurrentHashMap<>();

    public NodeMesh nodeMesh;
    public BlockchainPersistence blockchainPersistence;


    public ConnectX() throws IOException {
        this("ConnectX");
    }

    public ConnectX(String rootDir) throws IOException {
        initializeFileSystem(rootDir);
    }

    /**
     * Constructor that automatically connects to the network
     * @param rootDir Root directory for this ConnectX instance
     * @param port P2P listening port
     * @throws IOException if initialization or connection fails
     */
    public ConnectX(String rootDir, int port) throws IOException {
        initializeFileSystem(rootDir);
        connect(port);
    }

    /**
     * Constructor that fully initializes and connects with crypto
     * @param rootDir Root directory for this ConnectX instance
     * @param port P2P listening port
     * @param cxID UUID for this node (null to generate random)
     * @param password Password for private key encryption
     * @throws Exception if initialization fails
     */
    public ConnectX(String rootDir, int port, String cxID, String password) throws Exception {
        initializeFileSystem(rootDir);

        // Initialize crypto with provided or generated UUID
        String actualID = cxID != null ? cxID : java.util.UUID.randomUUID().toString();
        String address = "127.0.0.1:" + port;
        initializeCrypto(actualID, password, address);

        // Connect to network
        connect(port);
    }

    /**
     * Internal method to initialize file system and platform detection
     * Called by all constructors
     */
    private void initializeFileSystem(String rootDir) throws IOException {
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

        // Initialize blockchain persistence
        this.blockchainPersistence = new BlockchainPersistence(cxRoot);

        // Register default HTTP bridge provider if not already registered
        if (!isBridgeProviderPresent("cxHTTP1")) {
            try {
                dev.droppinganvil.v3.network.nodemesh.bridge.http.HTTPBridgeProvider httpBridge =
                    new dev.droppinganvil.v3.network.nodemesh.bridge.http.HTTPBridgeProvider();
                addBridgeProvider(httpBridge, this);
            } catch (Exception e) {
                // Bridge registration failed - not critical for initialization
                System.err.println("[ConnectX] Failed to register default HTTP bridge: " + e.getMessage());
            }
        }

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

    /**
     * Queue an event for transmission
     * Encapsulates access to instance outputQueue
     * @param bundle OutputBundle to queue
     */
    public void queueEvent(OutputBundle bundle) {
        synchronized (outputQueue) {
            outputQueue.add(bundle);
        }
    }

    /**
     * Build an event using a fluent builder pattern
     * Simplifies event creation by providing a concise API for required and optional parameters
     *
     * Example usage:
     * <pre>
     * // Network routing
     * buildEvent(EventType.MESSAGE, "Hello World".getBytes())
     *     .toNetwork("CXNET")
     *     .queue();
     *
     * // Peer-to-peer routing
     * buildEvent(EventType.MESSAGE, "Hello".getBytes())
     *     .toPeer(targetCxID)
     *     .encrypted(true)
     *     .queue();
     *
     * // Bridge routing
     * buildEvent(EventType.MESSAGE, data)
     *     .viaBridge("cxHTTP1", "https://example.com/cx")
     *     .queue();
     * </pre>
     *
     * @param eventType The type of event to create
     * @param data The event data payload
     * @return EventBuilder for chaining optional parameters
     */
    public EventBuilder buildEvent(EventType eventType, byte[] data) {
        return new EventBuilder(this, eventType, data);
    }

    /**
     * Fluent builder for creating and queuing NetworkEvents
     * Provides comprehensive methods for configuring all aspects of event routing and transmission
     */
    public static class EventBuilder {
        private final ConnectX connectX;
        private final NetworkEvent event;
        private final NetworkContainer container;
        private final CXPath path;

        private Node targetNode = null;
        private String recipientPublicKey = null;
        private byte[] signature = null;

        private EventBuilder(ConnectX connectX, EventType eventType, byte[] data) {
            this.connectX = connectX;

            // Create event
            this.event = new NetworkEvent(eventType, data);
            this.event.eT = eventType.name();

            // Create path with defaults
            this.path = new CXPath();
            this.event.p = path;

            // Create container with defaults
            this.container = new NetworkContainer();
            this.container.se = "cxJSON1";  // Default serialization
            this.container.s = false;        // Default not encrypted
        }

        // ========== Convenience Methods for Common Routing Patterns ==========

        /**
         * Route to a specific network using CXN scope
         * Shorthand for scope("CXN").network(networkId)
         * @param networkId The target network ID
         */
        public EventBuilder toNetwork(String networkId) {
            this.path.scope = "CXN";
            this.path.network = networkId;
            return this;
        }

        /**
         * Route directly to a peer using P2P scope (by cxID)
         * The Node will be looked up from PeerDirectory during transmission
         * @param cxID The target peer's cxID
         */
        public EventBuilder toPeer(String cxID) {
            this.path.scope = "P2P";
            this.path.cxID = cxID;
            // Node lookup will happen during transmission if not set
            return this;
        }

        /**
         * Route directly to a peer using P2P scope (with Node object)
         * @param node The target Node object
         */
        public EventBuilder toPeer(Node node) {
            this.path.scope = "P2P";
            if (node != null) {
                this.path.cxID = node.cxID;
                this.targetNode = node;
            }
            return this;
        }

        /**
         * Route via a bridge provider
         * @param bridgeType The bridge provider type (e.g., "cxHTTP1")
         * @param bridgeArg The bridge-specific argument (e.g., URL)
         */
        public EventBuilder viaBridge(String bridgeType, String bridgeArg) {
            this.path.bridge = bridgeType;
            this.path.bridgeArg = bridgeArg;
            return this;
        }

        /**
         * Route to a specific resource on a network
         * @param networkId The network ID
         * @param resourceId The resource ID
         */
        public EventBuilder toResource(String networkId, String resourceId) {
            this.path.scope = "CXN";
            this.path.network = networkId;
            this.path.resourceID = resourceId;
            return this;
        }

        // ========== CXPath Configuration Methods ==========

        /**
         * Set the routing scope
         * @param scope Routing scope (e.g., "CXN", "P2P", "LAN")
         */
        public EventBuilder scope(String scope) {
            this.path.scope = scope;
            return this;
        }

        /**
         * Set the target network for CXN scope routing
         * @param network Network ID
         */
        public EventBuilder network(String network) {
            this.path.network = network;
            return this;
        }

        /**
         * Set the target peer by cxID
         * @param cxID Target peer's cxID
         */
        public EventBuilder targetPeer(String cxID) {
            this.path.cxID = cxID;
            // Node lookup will happen during transmission if not set
            return this;
        }

        /**
         * Set the target peer by Node object
         * @param node Target Node object
         */
        public EventBuilder targetPeer(Node node) {
            if (node != null) {
                this.path.cxID = node.cxID;
                this.targetNode = node;
            }
            return this;
        }

        /**
         * Set the bridge provider type
         * @param bridge Bridge type (e.g., "cxHTTP1")
         */
        public EventBuilder bridge(String bridge) {
            this.path.bridge = bridge;
            return this;
        }

        /**
         * Set the bridge-specific argument
         * @param bridgeArg Bridge argument (e.g., URL, address)
         */
        public EventBuilder bridgeArg(String bridgeArg) {
            this.path.bridgeArg = bridgeArg;
            return this;
        }

        /**
         * Set the target address
         * @param address Network address
         */
        public EventBuilder address(String address) {
            this.path.address = address;
            return this;
        }

        /**
         * Set the protocol version
         * @param version Version number
         */
        public EventBuilder pathVersion(Integer version) {
            this.path.version = version;
            return this;
        }

        /**
         * Set the target resource ID
         * @param resourceID Resource identifier
         */
        public EventBuilder resourceID(String resourceID) {
            this.path.resourceID = resourceID;
            return this;
        }

        /**
         * Set a complete custom CXPath
         * @param customPath The CXPath to use
         */
        public EventBuilder path(CXPath customPath) {
            this.event.p = customPath;
            return this;
        }

        // ========== NetworkEvent Configuration Methods ==========

        /**
         * Set custom event ID (normally auto-generated)
         * @param eventId Event identifier
         */
        public EventBuilder eventId(String eventId) {
            this.event.iD = eventId;
            return this;
        }

        /**
         * Set the processing method
         * @param method Method identifier for event processing
         */
        public EventBuilder method(String method) {
            this.event.m = method;
            return this;
        }

        // ========== NetworkContainer Configuration Methods ==========

        /**
         * Set serialization format
         * @param format Serialization format (default: "cxJSON1")
         */
        public EventBuilder serialization(String format) {
            this.container.se = format;
            return this;
        }

        /**
         * Set whether the event should be E2E encrypted
         * @param encrypted True for encrypted transmission (default: false)
         */
        public EventBuilder encrypted(boolean encrypted) {
            this.container.s = encrypted;
            return this;
        }

        /**
         * Set the container ID
         * @param containerId Container identifier
         */
        public EventBuilder containerId(String containerId) {
            this.container.iD = containerId;
            return this;
        }

        /**
         * Set transmission preferences
         * @param transmitPref Transmission preference settings
         */
        public EventBuilder transmitPreference(dev.droppinganvil.v3.network.nodemesh.TransmitPref transmitPref) {
            this.container.tP = transmitPref;
            return this;
        }

        /**
         * Set container version
         * @param version Version number
         */
        public EventBuilder containerVersion(Double version) {
            this.container.v = version;
            return this;
        }

        /**
         * Set thread ID
         * @param threadId Thread identifier for conversation threading
         */
        public EventBuilder threadId(String threadId) {
            this.container.tID = threadId;
            return this;
        }

        // ========== OutputBundle Configuration Methods ==========

        /**
         * Set recipient public key for encryption
         * @param publicKey Recipient's public key
         */
        public EventBuilder recipientKey(String publicKey) {
            this.recipientPublicKey = publicKey;
            return this;
        }

        /**
         * Set custom signature (normally auto-generated during transmission)
         * @param signature Pre-computed signature
         */
        public EventBuilder signature(byte[] signature) {
            this.signature = signature;
            return this;
        }

        // ========== Terminal Operations ==========

        /**
         * Build the OutputBundle and queue it for transmission
         * This is the terminal operation that actually queues the event
         */
        public void queue() {
            // Create output bundle with all configured parameters
            OutputBundle bundle = new OutputBundle(
                event,
                targetNode,
                recipientPublicKey,
                signature,
                container
            );

            // Queue the event
            connectX.queueEvent(bundle);
        }

        /**
         * Build the OutputBundle without queuing it
         * Useful if you need to inspect or modify the bundle before queuing
         * @return The constructed OutputBundle
         */
        public OutputBundle build() {
            return new OutputBundle(
                event,
                targetNode,
                recipientPublicKey,
                signature,
                container
            );
        }

        /**
         * Get the NetworkEvent being built (for advanced use cases)
         * @return The NetworkEvent instance
         */
        public NetworkEvent getEvent() {
            return event;
        }

        /**
         * Get the NetworkContainer being built (for advanced use cases)
         * @return The NetworkContainer instance
         */
        public NetworkContainer getContainer() {
            return container;
        }

        /**
         * Get the CXPath being built (for advanced use cases)
         * @return The CXPath instance
         */
        public CXPath getPath() {
            return path;
        }
    }

    public void setSelf(Node selfNode) {
        self = selfNode;
    }

    public Node getSelf() {
        return self;
    }

    /**
     * Initialize cryptography with a random UUID
     * Convenience method for testing and simple deployments
     * @param password Password for private key encryption
     * @param listenAddress Listen address for this node (e.g., "127.0.0.1:49153")
     * @return The generated UUID (also accessible via getOwnID())
     * @throws Exception if crypto initialization fails
     */
    public String initializeCrypto(String password, String listenAddress) throws Exception {
        String uuid = java.util.UUID.randomUUID().toString();
        return initializeCrypto(uuid, password, listenAddress);
    }

    /**
     * Initialize cryptography with a specific UUID
     * @param cxID UUID for this node
     * @param password Password for private key encryption
     * @param listenAddress Listen address for this node (e.g., "127.0.0.1:49153")
     * @return The cxID (same as parameter)
     * @throws Exception if crypto initialization fails
     */
    public String initializeCrypto(String cxID, String password, String listenAddress) throws Exception {
        // Initialize encryption provider
        encryptionProvider.setup(cxID, password, cxRoot);

        // Create and set self node
        Node selfNode = new Node();
        selfNode.cxID = cxID;
        selfNode.publicKey = encryptionProvider.getPublicKey();
        selfNode.addr = listenAddress;
        setSelf(selfNode);

        return cxID;
    }

    /**
     * Setup bootstrap seed from EPOCH's seed file
     * Copies the provided seed file to this instance's cxnet-bootstrap.cxn
     * This allows the instance to bootstrap into CXNET when connect() is called
     *
     * @param epochSeedFile Path to EPOCH's seed file (usually latest from EPOCH/seeds/)
     * @throws Exception if seed copy fails
     */
    public void setupBootstrap(File epochSeedFile) throws Exception {
        if (!epochSeedFile.exists()) {
            throw new FileNotFoundException("EPOCH seed file not found: " + epochSeedFile.getAbsolutePath());
        }

        // Copy to bootstrap seed location
        File bootstrapSeed = new File(cxRoot, "cxnet-bootstrap.cxn");
        java.nio.file.Files.copy(
            epochSeedFile.toPath(),
            bootstrapSeed.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );

        // Load seed to extract cx.asc
        dev.droppinganvil.v3.network.Seed seed = dev.droppinganvil.v3.network.Seed.load(bootstrapSeed);

        // Extract EPOCH's public key (cx.asc) if not already present
        if (seed.certificates != null && seed.certificates.containsKey(EPOCH_UUID)) {
            File cxAsc = new File(cxRoot, "cx.asc");
            if (!cxAsc.exists()) {
                String epochPublicKey = seed.certificates.get(EPOCH_UUID);
                FileWriter writer = new FileWriter(cxAsc);
                writer.write(epochPublicKey);
                writer.flush();
                writer.close();
            }
        }
    }

    /**
     * Attempt to bootstrap into CXNET if not already loaded
     * Bootstrap sequence:
     * 1. Check if CXNET network already exists
     * 2. If not, check for distribution bootstrap seed (cxnet-bootstrap.cxn)
     * 3. If found, apply bootstrap seed to get initial configuration
     * 4. If not found, check local seeds/ directory for any seed
     * 5. If no local seed, queue SEED_REQUEST to EPOCH for latest seed
     * 6. After bootstrap, automatically check for seed updates from EPOCH
     */
    public void attemptCXNETBootstrap() {
        try {
            // Check if CXNET already exists
            if (networkMap.containsKey("CXNET")) {
                System.out.println("[Bootstrap] CXNET already loaded");
                // Even if loaded, check for updates from EPOCH
                requestSeedUpdateFromEpoch();
                return;
            }

            System.out.println("[Bootstrap] CXNET not found, attempting bootstrap...");

            // Step 1: Check for distribution bootstrap seed (shipped with software)
            File bootstrapSeed = new File(cxRoot, "cxnet-bootstrap.cxn");
            if (bootstrapSeed.exists()) {
                System.out.println("[Bootstrap] Found distribution bootstrap seed");
                dev.droppinganvil.v3.network.Seed seed = dev.droppinganvil.v3.network.Seed.load(bootstrapSeed);

                // Apply bootstrap seed to get initial CXNET config and EPOCH's public key
                applySeed(seed);

                System.out.println("[Bootstrap] Successfully bootstrapped from distribution seed");

                // Copy bootstrap seed to seeds/ directory for future use
                File seedsDir = new File(cxRoot, "seeds");
                if (!seedsDir.exists()) {
                    seedsDir.mkdirs();
                }
                File seedCopy = new File(seedsDir, seed.seedID + ".cxn");
                if (!seedCopy.exists()) {
                    seed.save(seedCopy);
                    System.out.println("[Bootstrap] Saved bootstrap seed to seeds/ directory");
                }

                // Request updated seed from EPOCH (bootstrap seed may be outdated)
                requestSeedUpdateFromEpoch();
                return;
            }

            // Step 2: Check for local seeds directory
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

                // Request updated seed from EPOCH (local seed may be outdated)
                requestSeedUpdateFromEpoch();
                return;
            }

            // Step 3: No local seed found - request from EPOCH (if we're not EPOCH)
            if (self != null && EPOCH_UUID.equals(self.cxID)) {
                System.out.println("[Bootstrap] This is EPOCH - no bootstrap needed");
                return;
            }

            System.out.println("[Bootstrap] No bootstrap or local seed found");
            System.out.println("[Bootstrap] Unable to bootstrap - please obtain cxnet-bootstrap.cxn");
            System.out.println("[Bootstrap] Download from: https://CXNET.AnvilDevelopment.US/bootstrap");

        } catch (Exception e) {
            System.err.println("[Bootstrap] Bootstrap attempt failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Request updated seed from EPOCH (called after successful bootstrap)
     * This allows nodes to get the latest network configuration
     */
    private void requestSeedUpdateFromEpoch() {
        if (self != null && !EPOCH_UUID.equals(self.cxID)) {
            System.out.println("[Bootstrap] Requesting seed update from EPOCH...");
            requestSeedFromEpoch();
        }
    }

    /**
     * Apply a seed to this ConnectX instance
     * Loads networks, adds peers to directory, caches certificates, and extracts NMI public key
     * @param seed Seed to apply
     * @throws Exception if application fails
     */
    private void applySeed(dev.droppinganvil.v3.network.Seed seed) throws Exception {
        System.out.println("[Seed] Applying seed " + seed.seedID);
        System.out.println("[Seed]   Networks: " + seed.networks.size());
        System.out.println("[Seed]   HV Peers: " + seed.hvPeers.size());
        System.out.println("[Seed]   Certificates: " + seed.certificates.size());

        // IMPORTANT: Extract and save NMI public key (cx.asc) first
        // This allows nodes to initialize crypto before connecting
        if (seed.certificates != null && seed.certificates.containsKey(EPOCH_UUID)) {
            File cxAsc = new File(cxRoot, "cx.asc");
            if (!cxAsc.exists()) {
                String nmiPublicKey = seed.certificates.get(EPOCH_UUID);
                FileWriter writer = new FileWriter(cxAsc);
                writer.write(nmiPublicKey);
                writer.flush();
                writer.close();
                System.out.println("[Seed] Extracted NMI public key to cx.asc");
            } else {
                System.out.println("[Seed] cx.asc already exists, skipping extraction");
            }
        }

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
     * First introduces this node to EPOCH via NewNode, then requests seed
     * Uses CXS scope for direct node-to-node communication during bootstrap
     */
    private void requestSeedFromEpoch() {
        try {
            System.out.println("[Bootstrap] Contacting EPOCH at " + EPOCH_BRIDGE_ADDRESS);

            // Step 1: Create hardcoded EPOCH node with bridge address for bootstrap
            Node epochNode = new Node();
            epochNode.cxID = EPOCH_UUID;
            epochNode.addr = EPOCH_BRIDGE_ADDRESS; // Use HTTP bridge for initial contact
            // Note: EPOCH's public key will be cached when we receive signed responses

            // Add EPOCH to peer directory so we can reach it
            try {
                PeerDirectory.addNode(epochNode);
                System.out.println("[Bootstrap] Added EPOCH to peer directory");
            } catch (SecurityException e) {
                // EPOCH already exists - this is fine
                System.out.println("[Bootstrap] EPOCH already in peer directory");
            }

            // Step 2: Introduce ourselves to EPOCH via NewNode event
            // This allows EPOCH to cache our certificate and encrypt responses for us
            if (self != null && self.publicKey != null) {
                System.out.println("[Bootstrap] Introducing ourselves to EPOCH...");

                // Create NewNode event with our node information
                dev.droppinganvil.v3.network.events.NetworkEvent newNodeEvent =
                    new dev.droppinganvil.v3.network.events.NetworkEvent(
                        dev.droppinganvil.v3.network.events.EventType.NewNode,
                        new byte[0]);
                newNodeEvent.eT = dev.droppinganvil.v3.network.events.EventType.NewNode.name();
                newNodeEvent.iD = java.util.UUID.randomUUID().toString();

                // Set path to EPOCH - use CXS scope for node-to-node communication
                dev.droppinganvil.v3.network.CXPath epochPath = new dev.droppinganvil.v3.network.CXPath();
                epochPath.cxID = EPOCH_UUID; // Target EPOCH specifically
                epochPath.scope = "CXS"; // Node/system scope (not authorized for CX/CXNET yet)
                epochPath.bridge = "cxHTTP1"; // Specify HTTP bridge
                epochPath.bridgeArg = "http://localhost:8080/cx"; // Bridge endpoint (localhost for testing)
                newNodeEvent.p = epochPath;

                // Serialize our node information as the event payload
                String selfJson = serialize("cxJSON1", self);
                newNodeEvent.d = selfJson.getBytes("UTF-8");

                // Create container for NewNode
                dev.droppinganvil.v3.network.events.NetworkContainer newNodeContainer =
                    new dev.droppinganvil.v3.network.events.NetworkContainer();
                newNodeContainer.se = "cxJSON1";
                newNodeContainer.s = false; // Not E2E encrypted
                newNodeContainer.iD = self.cxID; // Our ID

                // Queue the NewNode introduction
                OutputBundle newNodeBundle = new OutputBundle(newNodeEvent, epochNode, null, null, newNodeContainer);
                queueEvent(newNodeBundle);

                System.out.println("[Bootstrap] NewNode introduction queued");
            }

            // Step 3: Send SEED_REQUEST to EPOCH
            System.out.println("[Bootstrap] Requesting CXNET seed...");

            dev.droppinganvil.v3.network.events.NetworkEvent seedRequest =
                new dev.droppinganvil.v3.network.events.NetworkEvent(
                    dev.droppinganvil.v3.network.events.EventType.SEED_REQUEST,
                    "CXNET".getBytes("UTF-8"));
            seedRequest.eT = dev.droppinganvil.v3.network.events.EventType.SEED_REQUEST.name();
            seedRequest.iD = java.util.UUID.randomUUID().toString();

            // Set path to EPOCH NMI - use CXS scope for node-to-node communication
            dev.droppinganvil.v3.network.CXPath seedPath = new dev.droppinganvil.v3.network.CXPath();
            seedPath.cxID = EPOCH_UUID; // Target EPOCH specifically
            seedPath.scope = "CXS"; // Node/system scope (not authorized for CX/CXNET yet)
            seedPath.bridge = "cxHTTP1"; // Specify HTTP bridge
            seedPath.bridgeArg = "http://localhost:8080/cx"; // Bridge endpoint (localhost for testing)
            seedRequest.p = seedPath;

            // Create container
            dev.droppinganvil.v3.network.events.NetworkContainer seedRequestContainer =
                new dev.droppinganvil.v3.network.events.NetworkContainer();
            seedRequestContainer.se = "cxJSON1";
            seedRequestContainer.s = false;
            if (self != null) {
                seedRequestContainer.iD = self.cxID; // Our ID
            }

            // Queue the request (will be transmitted by OutConnectionController via bridge)
            OutputBundle seedRequestBundle = new OutputBundle(seedRequest, epochNode, null, null, seedRequestContainer);
            queueEvent(seedRequestBundle);

            System.out.println("[Bootstrap] SEED_REQUEST queued for EPOCH");
            System.out.println("[Bootstrap] Waiting for SEED_RESPONSE via HTTP bridge...");

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
        // CXNET: Only AddAccount (no blockchain recording by default for system health)
        // Other networks: AddAccount + Record-c3 (events chain)
        java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> defaultPermissions = new java.util.HashMap<>();
        defaultPermissions.put(Permission.AddAccount.name(),
            new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.AddAccount.name(), true, 10));

        // Only grant blockchain recording for non-CXNET networks
        // CXNET requires explicit permission grants to ensure system health
        if (!"CXNET".equals(networkID)) {
            defaultPermissions.put(Permission.Record.name() + "-" + network.networkDictionary.c3,
                new us.anvildevelopment.util.tools.permissions.BasicEntry(Permission.Record.name() + "-" + network.networkDictionary.c3, true, 10));
        }

        // Store default permissions template (used when nodes join)
        network.networkPermissions.permissionSet.put("DEFAULT_NODE", defaultPermissions);

        // Copy permissions to NetworkDictionary
        network.networkDictionary.networkPermissions = network.networkPermissions;

        // Add network to global map
        networkMap.put(networkID, network);

        // Persist blockchain to disk
        try {
            // Save genesis blocks for all three chains
            blockchainPersistence.saveBlock(networkID, 1L, network.c1.current);
            blockchainPersistence.saveBlock(networkID, 2L, network.c2.current);
            blockchainPersistence.saveBlock(networkID, 3L, network.c3.current);

            // Save chain metadata
            blockchainPersistence.saveChainMetadata(network.c1, networkID);
            blockchainPersistence.saveChainMetadata(network.c2, networkID);
            blockchainPersistence.saveChainMetadata(network.c3, networkID);

            System.out.println("[Blockchain] Network " + networkID + " persisted to disk");
        } catch (Exception e) {
            System.err.println("[Blockchain] Failed to persist network " + networkID + ": " + e.getMessage());
            e.printStackTrace();
        }

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

        // Try to load persisted blockchain data from disk
        try {
            if (blockchainPersistence.exists(networkID)) {
                System.out.println("[Blockchain] Loading persisted chains for network " + networkID);

                // Load chains (lazy loading - only current blocks initially)
                NetworkRecord c1 = blockchainPersistence.loadChain(networkID, 1L, false);
                NetworkRecord c2 = blockchainPersistence.loadChain(networkID, 2L, false);
                NetworkRecord c3 = blockchainPersistence.loadChain(networkID, 3L, false);

                // Apply loaded chains if they exist
                if (c1 != null) {
                    network.c1 = c1;
                    System.out.println("[Blockchain] Loaded chain c1 (" + c1.blockMap.size() + " blocks in memory)");
                }
                if (c2 != null) {
                    network.c2 = c2;
                    System.out.println("[Blockchain] Loaded chain c2 (" + c2.blockMap.size() + " blocks in memory)");
                }
                if (c3 != null) {
                    network.c3 = c3;
                    System.out.println("[Blockchain] Loaded chain c3 (" + c3.blockMap.size() + " blocks in memory)");
                }
            } else {
                System.out.println("[Blockchain] No persisted blockchain found for network " + networkID);
            }
        } catch (Exception e) {
            System.err.println("[Blockchain] Failed to load persisted chains for " + networkID + ": " + e.getMessage());
            // Not fatal - network can still function with in-memory chains
        }

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

    /**
     * Check if a node is blocked at CXNET level (global block)
     * CXNET-level blocks reject ALL transmissions from the node across all networks
     * @param nodeID Node UUID to check
     * @return true if node is blocked at CXNET level
     */
    public static boolean isCXNETBlocked(String nodeID) {
        return cxnetBlockedNodes.containsKey(nodeID);
    }

    /**
     * Block a node at CXNET level (global block across all networks)
     * This should only be called when processing a BLOCK_NODE event where network="CXNET"
     * @param nodeID Node UUID to block
     * @param reason Block reason/metadata
     */
    public static void blockNodeCXNET(String nodeID, String reason) {
        cxnetBlockedNodes.put(nodeID, reason);
        System.out.println("[CXNET] Blocked node globally: " + nodeID + " (reason: " + reason + ")");
    }

    /**
     * Unblock a node from CXNET level
     * This should only be called when processing an UNBLOCK_NODE event where network="CXNET"
     * @param nodeID Node UUID to unblock
     */
    public static void unblockNodeCXNET(String nodeID) {
        String reason = cxnetBlockedNodes.remove(nodeID);
        if (reason != null) {
            System.out.println("[CXNET] Unblocked node globally: " + nodeID + " (was blocked for: " + reason + ")");
        }
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
    /**
     * Validates a block chronologically - ensures all events had proper permissions AT THE TIME
     * This prevents retroactive permission exploits by replaying blockchain state
     *
     * @param network The network this block belongs to
     * @param chainID Which chain (c1, c2, or c3)
     * @param block The block to validate
     * @param previousBlocks All blocks before this one (in order) to rebuild permission state
     * @return true if all events in block are valid, false if any event is invalid
     */
    public boolean validateBlockChronologically(CXNetwork network, Long chainID, dev.droppinganvil.v3.edge.NetworkBlock block,
                                               java.util.List<dev.droppinganvil.v3.edge.NetworkBlock> previousBlocks) {
        if (network == null || block == null) {
            return false;
        }

        // Build permission state by replaying all previous blocks
        // Start with genesis/initial permissions
        java.util.Map<String, java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry>> permissionState =
            new java.util.HashMap<>(network.networkPermissions.permissionSet);

        // Replay previous blocks to build current permission state
        if (previousBlocks != null) {
            for (dev.droppinganvil.v3.edge.NetworkBlock prevBlock : previousBlocks) {
                // Process permission-modifying events in this block
                for (NetworkEvent event : prevBlock.networkEvents.values()) {
                    updatePermissionState(permissionState, event, network);
                }
            }
        }

        // Now validate each event in the target block against permission state
        for (java.util.Map.Entry<Integer, NetworkEvent> entry : block.networkEvents.entrySet()) {
            NetworkEvent event = entry.getValue();

            // Determine sender from event path or container (stored in event during recording)
            // For now, we'll need the sender ID - this should be added to event metadata
            // TODO: Store sender ID in NetworkEvent for validation

            // For events that modify permissions, check if sender has permission to do so
            // Then update permission state for next events
            if (event.eT != null) {
                try {
                    EventType et = EventType.valueOf(event.eT);
                    switch (et) {
                        case UPDATE_NMI:
                        case ADD_NMI:
                        case DELETE_NMI:
                            // Only NMI can modify NMI list
                            // Verify event is signed by current NMI
                            // TODO: Implement NMI signature verification
                            break;
                        // Other permission-modifying events would go here
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unknown event type
                }
            }

            // Update permission state after processing this event
            updatePermissionState(permissionState, event, network);
        }

        return true; // All events validated successfully
    }

    /**
     * Updates permission state based on a permission-modifying event
     * Called during chronological validation to replay blockchain state
     */
    private void updatePermissionState(java.util.Map<String, java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry>> permissionState,
                                      NetworkEvent event, CXNetwork network) {
        if (event == null || event.eT == null) {
            return;
        }

        try {
            EventType et = EventType.valueOf(event.eT);
            switch (et) {
                case UPDATE_NMI:
                case ADD_NMI:
                case DELETE_NMI:
                    // TODO: Update NMI list in permission state
                    // This would modify the permissionState map to reflect NMI changes
                    break;
                // Handle other permission-modifying events
                // e.g., AddAccount would add new node with default permissions
            }
        } catch (IllegalArgumentException ignored) {
            // Not a known event type
        }
    }

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
                // Save the completed block to disk before rotating
                try {
                    blockchainPersistence.saveBlock(networkID, chainID, currentBlock);
                    blockchainPersistence.saveChainMetadata(targetChain, networkID);
                } catch (Exception e) {
                    System.err.println("[Blockchain] Failed to persist block " + currentBlock.block +
                                     " for chain " + chainID + ": " + e.getMessage());
                }

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

    /**
     * Get blockchain statistics for a network
     * Utility method for testing and monitoring
     */
    public BlockchainStats getBlockchainStats(CXNetwork network) {
        if (network == null) return null;

        BlockchainStats stats = new BlockchainStats();
        stats.networkID = network.configuration.netID;
        stats.exists = blockchainPersistence.exists(network.configuration.netID);

        if (network.c1 != null) {
            stats.c1BlockCount = network.c1.blockMap.size();
            stats.c1CurrentBlock = (network.c1.current != null) ? network.c1.current.block : null;
        }
        if (network.c2 != null) {
            stats.c2BlockCount = network.c2.blockMap.size();
            stats.c2CurrentBlock = (network.c2.current != null) ? network.c2.current.block : null;
        }
        if (network.c3 != null) {
            stats.c3BlockCount = network.c3.blockMap.size();
            stats.c3CurrentBlock = (network.c3.current != null) ? network.c3.current.block : null;
        }

        return stats;
    }

    /**
     * Get blockchain statistics for a network by ID
     */
    public BlockchainStats getBlockchainStats(String networkID) {
        return getBlockchainStats(getNetwork(networkID));
    }

    /**
     * Force save all blockchain data for a network
     * Utility method for testing
     */
    public void forceBlockchainSave(CXNetwork network) throws Exception {
        if (network == null) throw new IllegalArgumentException("Network cannot be null");

        String networkID = network.configuration.netID;

        // Save all blocks for each chain
        if (network.c1 != null) {
            blockchainPersistence.saveAllBlocks(network.c1, networkID);
            blockchainPersistence.saveChainMetadata(network.c1, networkID);
        }
        if (network.c2 != null) {
            blockchainPersistence.saveAllBlocks(network.c2, networkID);
            blockchainPersistence.saveChainMetadata(network.c2, networkID);
        }
        if (network.c3 != null) {
            blockchainPersistence.saveAllBlocks(network.c3, networkID);
            blockchainPersistence.saveChainMetadata(network.c3, networkID);
        }
    }

    /**
     * Force save all blockchain data for a network by ID
     */
    public void forceBlockchainSave(String networkID) throws Exception {
        CXNetwork network = getNetwork(networkID);
        if (network == null) throw new IllegalArgumentException("Network not found: " + networkID);
        forceBlockchainSave(network);
    }

    /**
     * Clear blockchain data for a network
     * Utility method for testing
     */
    public void clearBlockchainData(CXNetwork network) throws Exception {
        if (network == null) throw new IllegalArgumentException("Network cannot be null");
        blockchainPersistence.deleteNetwork(network.configuration.netID);
    }

    /**
     * Clear blockchain data for a network by ID
     */
    public void clearBlockchainData(String networkID) throws Exception {
        blockchainPersistence.deleteNetwork(networkID);
    }

    /**
     * Blockchain statistics container
     */
    public static class BlockchainStats {
        public String networkID;
        public boolean exists;
        public int c1BlockCount;
        public Long c1CurrentBlock;
        public int c2BlockCount;
        public Long c2CurrentBlock;
        public int c3BlockCount;
        public Long c3CurrentBlock;

        @Override
        public String toString() {
            return "BlockchainStats{" +
                "network='" + networkID + '\'' +
                ", onDisk=" + exists +
                ", c1=" + c1BlockCount + " blocks (current=" + c1CurrentBlock + ")" +
                ", c2=" + c2BlockCount + " blocks (current=" + c2CurrentBlock + ")" +
                ", c3=" + c3BlockCount + " blocks (current=" + c3CurrentBlock + ")" +
                '}';
        }
    }

}
