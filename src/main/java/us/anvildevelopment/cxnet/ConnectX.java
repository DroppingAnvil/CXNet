/*
 * Copyright (c) 2021 Christopher Willett
 * All Rights Reserved.
 */

package us.anvildevelopment.cxnet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.api.CXPlugin;
import us.anvildevelopment.cxnet.api.DataLevel;
import us.anvildevelopment.cxnet.crypt.core.CryptProvider;
import us.anvildevelopment.cxnet.crypt.pgpainless.PainlessCryptProvider;
import us.anvildevelopment.cxnet.edge.NetworkRecord;
import us.anvildevelopment.cxnet.edge.DataContainer;
import us.anvildevelopment.cxnet.edge.NetworkBlock;
import us.anvildevelopment.cxnet.exceptions.UnsafeKeywordException;
import us.anvildevelopment.cxnet.io.IOJob;
import us.anvildevelopment.cxnet.io.strings.JacksonProvider;
import us.anvildevelopment.cxnet.io.strings.SerializationProvider;
import us.anvildevelopment.cxnet.network.*;
import us.anvildevelopment.cxnet.network.events.*;
import us.anvildevelopment.cxnet.network.nodemesh.*;
import us.anvildevelopment.cxnet.network.nodemesh.OutputBundle;
import us.anvildevelopment.cxnet.network.nodemesh.PeerDirectory;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.BridgeProvider;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.CXBridge;
import us.anvildevelopment.cxnet.network.nodemesh.bridge.http.HTTPBridgeProvider;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ConnectX {
    private static final Logger log = LoggerFactory.getLogger(ConnectX.class);
    // EPOCH NMI Bootstrap Constants
    public static final String EPOCH_UUID = "00000000-0000-0000-0000-000000000001";

    // TODO: Bootstrap URL Configuration
    // PRODUCTION: Switch to public URL once RProx/Cloudflare configuration is verified
    // - Verify health endpoint works through Cloudflare
    // - Test bootstrap process end-to-end
    // - Update to: cxHTTP1:https://CXNET.AnvilDevelopment.US/cx
    // public static final String EPOCH_BRIDGE_ADDRESS = "cxHTTP1:http://localhost:8080/cx";
    public static final String EPOCH_BRIDGE_ADDRESS = "cxHTTP1:https://CXNET.AnvilDevelopment.US/cx";

    public static Platform platform;
    public State state = State.CXConnecting;
    // IMPORTANT: Per-instance network map to support multiple ConnectX instances in same JVM
    private final ConcurrentHashMap<String, CXNetwork> networkMap = new ConcurrentHashMap<>();
    public CryptProvider encryptionProvider = null;
    private static final transient ConcurrentHashMap<String, SerializationProvider> serializationProviders = new ConcurrentHashMap<>();
    /** @deprecated Use per-instance bridge providers via {@link #addBridgeProvider} instead. */
    @Deprecated
    public static final ConcurrentHashMap<String, CXBridge> bridgeMap = new ConcurrentHashMap<>();

    // IMPORTANT: Per-instance bridge providers to support multiple ConnectX instances in same JVM
    // Each instance gets its own bridge provider instances (e.g., separate HTTP servers on different ports)
    private final ConcurrentHashMap<String, BridgeProvider> bridgeProviders = new ConcurrentHashMap<>();

    public final Queue<IOJob> jobQueue = new ConcurrentLinkedQueue<>();
    // IMPORTANT: Instance queues (not static) to support multiple ConnectX instances in same JVM
    // Each instance has its own queues and processors to avoid cross-instance contamination
    public final Queue<InputBundle> eventQueue = new ConcurrentLinkedQueue<>();
    public final Queue<OutputBundle> outputQueue = new ConcurrentLinkedQueue<>();
    // Retry queue for failed events that need to be retried with exponential backoff
    // This prevents failed events (e.g., to offline EPOCH) from blocking the main output queue
    public final Queue<RetryBundle> retryQueue = new ConcurrentLinkedQueue<>();
    public File cxRoot = new File("ConnectX");
    public File nodemesh;
    public File resources;
    /** True when this node has no bootstrap seed and should request one via CXHELLO */
    public volatile boolean bootstrapSearch = false;
    /** Guards against concurrent/duplicate bootstrap attempts */
    private final java.util.concurrent.atomic.AtomicBoolean bootstrapStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** NMI-signed seed blob to share with peers that request one. Only valid if signed by EPOCH. */
    public byte[] signedBootstrapSeed = null;
    private transient Node self;
    public int listeningPort = 0;
    private final ConcurrentHashMap<String, CXPlugin> plugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, us.anvildevelopment.cxnet.app.CXAppServer> appServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, us.anvildevelopment.cxnet.app.CXAppClient> appClients = new ConcurrentHashMap<>();
    private static final transient List<String> reserved = Arrays.asList("SYSTEM", "CX", "cxJSON1", "CXNET");

    /**
     * CXNET-level blocked nodes (global blocks across all networks)
     * Populated by reading BLOCK_NODE events from CXNET's c1 (Admin) chain where network="CXNET"
     * Key: Node UUID
     * Value: Block reason/metadata
     *
     * When a node is blocked at CXNET level, ALL transmissions from that node are rejected
     * This is separate from network-specific blocks (stored in CXNetwork.blockedNodes)
     */
    private final ConcurrentHashMap<String, String> cxnetBlockedNodes = new ConcurrentHashMap<>();

    public NodeMesh nodeMesh;
    public BlockchainPersistence blockchainPersistence;
    public DataContainer dataContainer;
    public us.anvildevelopment.cxnet.network.stream.CXStreamManager streamManager;
    /** In-flight CHECK_NETWORK_NAME callbacks keyed by requestId. Called by NodeMesh on response. */
    public final ConcurrentHashMap<String, Consumer<us.anvildevelopment.cxnet.network.events.NetworkNameCheck>> pendingNameChecks = new ConcurrentHashMap<>();

    /**
     * Seed consensus collection for multi-peer verification
     * Stores SEED_RESPONSE messages from multiple peers for consensus voting
     * Key: Network ID (e.g., "CXNET")
     * Value: Map of peer ID -> SeedResponseData
     */
    public static class SeedResponseData {
        public Seed dynamicSeed;
        public byte[] epochSeedBlob;
        public boolean authoritative;
        public String senderID;
        public long timestamp;
        public java.util.Map<String, Number> chainHeights;
    }
    public final ConcurrentHashMap<String, ConcurrentHashMap<String, SeedResponseData>> seedConsensusMap = new ConcurrentHashMap<>();

    /**
     * Block consensus tracker for zero trust multi-peer verification
     * Manages block requests and responses from multiple peers to reach consensus
     * Used when network.zT = true (zero trust mode activated)
     */
    public final BlockConsensusTracker blockConsensusTracker = new BlockConsensusTracker();

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

        // Address is optional - nodes can rely solely on HTTP bridge addresses
        // For nodes with direct P2P connectivity, address will be discovered via CXHELLO
        String address = null;  // Will be populated by LAN discovery or bridge
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

        // Initialize nodeMesh.peerDirectory.peers for signed node persistence
        //nodeMesh.peerDirectory.peers = nodemesh;

        resources = new File(nodemesh, "nodemesh-resources");
        if (!resources.exists()) if (!resources.mkdir()) throw new IOException();

        // Initialize blockchain persistence
        this.blockchainPersistence = new BlockchainPersistence(cxRoot);

        // Initialize or load DataContainer
        loadDataContainer();

        // Register default HTTP bridge provider (per-instance)
        // Each ConnectX instance gets its own HTTPBridgeProvider for independent HTTP servers
        if (!isBridgeProviderPresent("cxHTTP1")) {
            try {
                HTTPBridgeProvider httpBridge =
                    new HTTPBridgeProvider();
                addBridgeProvider(httpBridge);
            } catch (Exception e) {
                // Bridge registration failed - not critical for initialization
                System.err.println("[ConnectX] Failed to register default HTTP bridge: " + e.getMessage());
            }
        }
        encryptionProvider = new PainlessCryptProvider(this);
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

    // Bridge Provider Management (per-instance for multi-peer support)
    public void addBridgeProvider(BridgeProvider provider) throws UnsafeKeywordException, IllegalAccessException {
        String protocol = provider.getBridgeProtocol();
        checkSafety(protocol);
        if (bridgeProviders.containsKey(protocol)) {
            throw new IllegalAccessException("Bridge provider " + protocol + " already registered for this instance");
        }
        provider.initialize(this);
        bridgeProviders.put(protocol, provider);
    }

    public BridgeProvider getBridgeProvider(String protocol) {
        return bridgeProviders.get(protocol);
    }

    public boolean isBridgeProviderPresent(String protocol) {
        return bridgeProviders.containsKey(protocol);
    }

    /** Returns all registered bridge providers (used by CXStreamManager for bridge selection). */
    public java.util.Collection<BridgeProvider> getBridgeProviders() {
        return bridgeProviders.values();
    }
    // Instance methods for signing (use these for proper per-instance crypto)
    public Object getSignedObject(String cxID, InputStream is, Class<?> clazz, String method) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (encryptionProvider.verifyAndStrip(is, baos, cxID)) {
            return deserialize(method, baos.toString(StandardCharsets.UTF_8), clazz);
        }
        return null;
    }
    public Object getSignedObjectNoVerify(InputStream is, Class<?> clazz, String method) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        encryptionProvider.stripSignature(is, baos);
        return deserialize(method, baos.toString(StandardCharsets.UTF_8), clazz);
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
        if (bundle != null && bundle.ne != null && bundle.ne.eT != null && bundle.ne.eT.contains("HELLO")) {
            String targetPeer = (bundle.n != null && bundle.n.cxID != null)
                ? bundle.n.cxID.substring(0, 8)
                : (bundle.ne.p != null && bundle.ne.p.cxID != null ? bundle.ne.p.cxID.substring(0, 8) : "broadcast");
            log.debug("[queueEvent-DEBUG] Queuing {} to peer {}, queue size before: {}", bundle.ne.eT, targetPeer, outputQueue.size());
        }
        outputQueue.add(bundle);
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
     * Fluent builder for creating and queuing NetworkEvents over the CX protocol.
     *
     * <p>Create an instance with {@link ConnectX#buildEvent(EventType, byte[])}. Chain the
     * configuration methods you need, then call {@link #queue()} to submit.</p>
     *
     * <h2>Routing scopes</h2>
     * <ul>
     *   <li><b>CXS</b> - Single-peer delivery. Use {@link #toPeer(String)} or {@link #toPeer(Node)}.
     *       The event is delivered only to that one node.</li>
     *   <li><b>CXN</b> - Network broadcast. Use {@link #toNetwork(String, Long)}.
     *       The event is fanned out to every peer in the named network.</li>
     * </ul>
     *
     * <h2>Signing vs encryption</h2>
     * <ul>
     *   <li>{@link #signData()} - Signs {@code event.d} with the sender's key so the receiver can
     *       verify authenticity. Required for most CXS events that are not E2E-encrypted.
     *       The signed blob replaces {@code event.d}.</li>
     *   <li>{@link #addRecipient(String)} + {@link #encrypt()} - End-to-end encrypts {@code event.d}
     *       for the listed recipients. Sets {@code event.e2e = true}. Call
     *       {@code addRecipient} before {@code encrypt}, and call {@code encrypt} before
     *       {@code toPeer} / {@code queue}. <strong>Do not also call {@code signData()} when
     *       using E2E</strong> - the encryption layer handles authentication.</li>
     * </ul>
     *
     * <h2>Common patterns</h2>
     *
     * <p><b>1. Plain signed message to a peer (most common for protocol events)</b></p>
     * <pre>{@code
     * connectX.buildEvent(EventType.MESSAGE, "hello".getBytes("UTF-8"))
     *     .toPeer("00000000-0000-0000-0000-000000000001")
     *     .signData()
     *     .queue();
     * }</pre>
     *
     * <p><b>2. End-to-end encrypted message to a peer</b></p>
     * <pre>{@code
     * String recipientID = "00000000-0000-0000-0000-000000000001";
     * connectX.buildEvent(EventType.MESSAGE, "secret".getBytes("UTF-8"))
     *     .addRecipient(recipientID)   // who can decrypt
     *     .encrypt()                   // encrypt BEFORE routing/queue
     *     .toPeer(recipientID)         // where to deliver
     *     .queue();
     * }</pre>
     *
     * <p><b>3. Network broadcast (CXN) recorded to the event chain</b></p>
     * <pre>{@code
     * // network.networkDictionary.c3 is the events chain ID
     * connectX.buildEvent(EventType.REGISTER_NODE,
     *         ConnectX.serialize("cxJSON1", myNodeRegistration).getBytes("UTF-8"))
     *     .toNetwork("MYNET", network.networkDictionary.c3)
     *     .withRecordFlag(true)
     *     .queue();
     * }</pre>
     *
     * <p><b>4. Signed network management event to a specific peer</b></p>
     * <pre>{@code
     * NodeModeration mod = new NodeModeration("CXNET", targetNodeID, "reason");
     * connectX.buildEvent(EventType.BLOCK_NODE,
     *         ConnectX.serialize("cxJSON1", mod).getBytes("UTF-8"))
     *     .toPeer("00000000-0000-0000-0000-000000000001")
     *     .signData()
     *     .queue();
     * }</pre>
     *
     * <p><b>5. Routing via a bridge (e.g. HTTP reverse proxy)</b></p>
     * <pre>{@code
     * connectX.buildEvent(EventType.MESSAGE, data)
     *     .toPeer("00000000-0000-0000-0000-000000000001")
     *     .viaBridge("cxHTTP1", "https://cx7.anvildevelopment.us/cx")
     *     .signData()
     *     .queue();
     * }</pre>
     *
     * <h2>Common mistakes</h2>
     * <ul>
     *   <li><strong>Called {@code addRecipient()} but forgot {@code encrypt()}</strong> - the data
     *       is sent in the clear and a WARNING is printed. Always follow
     *       {@code addRecipient} with {@code encrypt}.</li>
     *   <li><strong>Called {@code encrypt()} without any {@code addRecipient()}</strong> - throws
     *       immediately. Add at least one recipient first.</li>
     *   <li><strong>Called {@code signData()} on an E2E message</strong> - unnecessary and can
     *       break decryption. Use one or the other.</li>
     *   <li><strong>Called {@code toNetwork(String)} without a chainID</strong> - the overload
     *       that omits chainID is deprecated; prefer
     *       {@link #toNetwork(String, Long)} to specify which chain records the event.</li>
     * </ul>
     *
     * <h2>Recommended call order</h2>
     * <ol>
     *   <li>Optional: {@code addRecipient()} (one or more)</li>
     *   <li>Optional: {@code encrypt()} (E2E path) <em>or</em> {@code signData()} (signed path)</li>
     *   <li>{@code toPeer()} or {@code toNetwork()} (sets routing scope)</li>
     *   <li>Optional: {@code viaBridge()}, {@code withRecordFlag()}, etc.</li>
     *   <li>{@code queue()} (terminal - submits the event)</li>
     * </ol>
     */
    public static class EventBuilder {
        private final ConnectX connectX;
        private final NetworkEvent event;
        private final NetworkContainer container;
        private final CXPath path;
        private boolean lookupWait = false;

        private Node targetNode = null;
        private String recipientPublicKey = null;
        private byte[] signature = null;
        private java.util.List<String> encryptionRecipients = new java.util.ArrayList<>();
        private boolean doSign = false;
        private boolean doEncrypt = false;
        private boolean doLowLevel = false;
        private String fanOutQd = null;

        private EventBuilder(ConnectX connectX, EventType eventType, byte[] data) {
            this.connectX = connectX;

            // Create event
            this.event = new NetworkEvent(eventType, data);
            this.event.eT = eventType.name();
            this.event.sid = java.util.UUID.randomUUID().toString();

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
         * Override the auto-generated sid. Use on responses to echo back the request's sid.
         */
        public EventBuilder withSid(String sid) {
            this.event.sid = sid;
            return this;
        }

        /**
         * Route to a specific network using CXN scope
         * Shorthand for scope("CXN").network(networkId)
         * @param networkId The target network ID
         * @deprecated Use toNetwork(networkId, chainID) to specify blockchain recording chain
         */
        public EventBuilder toNetwork(String networkId) {
            this.path.scope = "CXN";
            this.path.network = networkId;
            this.path.cxID = "*";  // Broadcast to all network peers by default
            return this;
        }

        /**
         * Route to a specific network using CXN scope with blockchain recording
         * @param networkId The target network ID
         * @param chainID The chain ID to record events to (e.g., network.networkDictionary.c3 for events)
         */
        public EventBuilder toNetwork(String networkId, Long chainID) {
            this.path.scope = "CXN";
            this.path.network = networkId;
            this.path.chainID = chainID;
            this.path.cxID = "*";  // Broadcast to all network peers by default
            return this;
        }

        /**
         * Route directly to a peer using P2P scope (by cxID)
         * The Node will be looked up from PeerDirectory during transmission
         * @param cxID The target peer's cxID
         */
        public EventBuilder toPeer(String cxID) {
            this.path.scope = "CXS";  // CXS = ConnectX Secure (single peer transmission)
            this.path.cxID = cxID;
            // Node lookup will happen during transmission if not set
            return this;
        }


        /**
         * Route directly to a peer using P2P scope (with Node object)
         * @param node The target Node object
         */
        public EventBuilder toPeer(Node node) {
            this.path.scope = "CXS";  // CXS = ConnectX Secure (single peer transmission)
            if (node != null) {
                this.path.cxID = node.cxID;
                this.targetNode = node;
            }
            return this;
        }

        /**
         * Route via CXN mesh to a specific peer (proxy routing).
         * Unlike toPeer() (CXS direct), this travels through the network mesh so
         * no direct route is required. Relay nodes forward toward the target cxID.
         * Use when the target is known through the network but has no direct address.
         * @param networkId The network to route through
         * @param targetCxID The target peer's cxID
         */
        public EventBuilder toNetworkPeer(String networkId, String targetCxID) {
            this.path.scope = "CXN";
            this.path.network = networkId;
            this.path.cxID = targetCxID;
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
         * Route to every peer in the given network's backend set (scope CXB).
         * OutConnectionController fans the single OutputBundle out to each backend.
         * All copies share the same qd so recipients drop duplicates after the first.
         * A random qd is generated; use toBackends(networkID, queryId) to supply your own
         * so you can correlate the response via ConnectX.pendingNameChecks.
         * @param networkID The network whose backendSet to target
         */
        public EventBuilder toBackends(String networkID) {
            return toBackends(networkID, java.util.UUID.randomUUID().toString());
        }

        /**
         * Route to every peer in the given network's backend set with a caller-supplied qd.
         * @param networkID The network whose backendSet to target
         * @param queryId   Shared deduplication / correlation ID for this fan-out
         */
        public EventBuilder toBackends(String networkID, String queryId) {
            this.path.scope = "CXB";
            this.path.network = networkID;
            this.container.qd = queryId;
            this.fanOutQd = queryId;
            return this;
        }

        /**
         * Returns the qd assigned by toBackends(), or null if not a fan-out.
         * Store this before calling queue() if you need to correlate a response future.
         */
        public String getQueryId() {
            return fanOutQd;
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
         * Set the target blockchain chain ID for recording
         * @param chainID Chain identifier (c1, c2, or c3)
         */
        public EventBuilder chainID(Long chainID) {
            this.path.chainID = chainID;
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

        /**
         * Set the auto-record flag
         * When true, event will be automatically recorded to blockchain if sender has Record permission
         * @param record True to enable automatic recording (default: false)
         */
        public EventBuilder withRecordFlag(boolean record) {
            this.event.r = record;
            return this;
        }

        /**
         * Set the executeOnSync flag
         * When true, event will be executed during blockchain sync to rebuild state
         * @param executeOnSync True for state-modifying events (default: false)
         */
        public EventBuilder withExecuteOnSync(boolean executeOnSync) {
            this.event.executeOnSync = executeOnSync;
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
        public EventBuilder transmitPreference(TransmitPref transmitPref) {
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

        /**
         *  !IMPORTANT!
         * Sign the event data payload
         * Replaces event.d with a signed blob, preserving the original signature for relay
         * Used for NewNode events where the Node blob must be signed by the sender
         * IF E2E is not in use signData IS REQUIRED, it will fail to pass CX cryptography
         * @return This builder for chaining
         */
        public EventBuilder signData() {
            this.doSign = true;
            return this;
        }

        /**
         * Add a recipient for E2E encryption
         * Can be called multiple times to add multiple recipients
         * Provider should encrypt the message so all listed recipients can decrypt it
         * @param cxID Recipient's cxID
         * @return This builder for chaining
         */
        public EventBuilder addRecipient(String cxID) {
            if (cxID != null && !cxID.isEmpty()) {
                this.encryptionRecipients.add(cxID);
            }
            return this;
        }

        /**
         * !IMPORTANT!
         * Encrypt the event data payload for all added recipients (E2E encryption)
         * Sets the e2e flag to true so receiver knows to decrypt
         * Replaces event.d with encrypted blob that can only be read by recipients
         * Use addRecipient() before calling this to specify who can decrypt
         * If not using signData(), encrypt() is REQUIRED
         * @return This builder for chaining
         */
        public EventBuilder encrypt() {
            if (encryptionRecipients.isEmpty()) {
                throw new RuntimeException("No encryption recipients specified - call addRecipient() first");
            }
            this.doEncrypt = true;
            return this;
        }

        /**
         * Low-level direct transmission to a raw address (IP:port or bridge).
         * The event is spec-compliant (scope="CXS", oCXID set, etc.).
         * The ll flag tells OutConnectionController to route via out.n.addr directly
         * instead of resolving by cxID -- used when the peer's cxID is not yet known.
         * @param address Target address, e.g. "192.168.1.5:49152"
         */
        public EventBuilder lowLevel(String address) {
            this.doLowLevel = true;
            this.path.scope = "CXS";
            this.targetNode = new Node();
            this.targetNode.addr = address;
            return this;
        }

        // ========== Terminal Operations ==========

        /**
         * Build the OutputBundle and queue it for transmission
         * This is the terminal operation that actually queues the event
         */
        public void queue() {
            us.anvildevelopment.cxnet.io.IOJob job = new us.anvildevelopment.cxnet.io.IOJob(this);
            synchronized (connectX.jobQueue) {
                connectX.jobQueue.add(job);
            }
        }

        /**
         * Called by IOThread (BUILD_OUTPUT): performs deferred sign/encrypt then places OutputBundle on outputQueue.
         */
        public void execute() {
            try {
                if (doSign) {
                    ByteArrayInputStream dataInput = new ByteArrayInputStream(this.event.d);
                    ByteArrayOutputStream signedOutput = new ByteArrayOutputStream();
                    connectX.encryptionProvider.sign(dataInput, signedOutput);
                    this.event.d = signedOutput.toByteArray();
                }

                if (doEncrypt) {
                    ByteArrayInputStream dataInput = new ByteArrayInputStream(this.event.d);
                    ByteArrayOutputStream encryptedOutput = new ByteArrayOutputStream();
                    connectX.encryptionProvider.encrypt(dataInput, encryptedOutput, encryptionRecipients);
                    this.event.d = encryptedOutput.toByteArray();
                    this.event.e2e = true;
                    log.info("[E2E] Encrypted event data for " + encryptionRecipients.size() + " recipients");
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to prepare event data", e);
            }

            if (this.path.oCXID == null && connectX.self != null) {
                this.path.oCXID = connectX.self.cxID;
            }
            container.p = path;
            if (this.path.scope.equals("CXS") && this.targetNode == null) {
                targetNode = new Node();
                targetNode.cxID = this.path.cxID;
            }

            OutputBundle bundle = new OutputBundle(
                event,
                targetNode,
                recipientPublicKey,
                signature,
                container
            );
            bundle.ll = doLowLevel;
            connectX.queueEvent(bundle);
        }

        /**
         * Build the OutputBundle without queuing it
         * Useful if you need to inspect or modify the bundle before queuing
         * @return The constructed OutputBundle
         */
        public OutputBundle build() {
            // Set origin CXID to sender's actual ID for signature verification
            if (this.path.oCXID == null && connectX.self != null) {
                this.path.oCXID = connectX.self.cxID;
            }

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
        // Purge self from peer directory in case stale data was loaded before identity was set
        if (selfNode != null && nodeMesh != null) {
            purgeFromPeerDirectory(nodeMesh.peerDirectory);
        }
    }

    /**
     * Returns true if the given node represents this node itself.
     * Checks both cxID and public key so a stale entry with a different ID
     * but our key is also caught.
     */
    public boolean isSelfNode(Node node) {
        if (node == null) return false;
        if (node.cxID != null && node.cxID.equals(getOwnID())) return true;
        if (node.publicKey != null) {
            String ownKey = encryptionProvider != null ? encryptionProvider.getPublicKey() : null;
            if (ownKey != null && ownKey.equals(node.publicKey)) return true;
        }
        return false;
    }

    /** Remove all self entries from all peer directory maps. Checks both cxID and public key. */
    private void purgeFromPeerDirectory(PeerDirectory pd) {
        if (pd == null) return;
        purgeSelfFromMap(pd.hv);
        purgeSelfFromMap(pd.seen);
        purgeSelfFromMap(pd.lan);
        purgeSelfFromMap(pd.peerCache);
    }

    private void purgeSelfFromMap(java.util.concurrent.ConcurrentHashMap<String, Node> map) {
        if (map == null) return;
        map.entrySet().removeIf(stringNodeEntry -> isSelfNode(stringNodeEntry.getValue()));
    }

    public Node getSelf() {
        return self;
    }

    /**
     * Update self.networks to reflect current networkMap membership.
     * Call after any networkMap.put so the signed node blob stays current.
     */
    private void refreshSelfNetworks() {
        if (self == null) return;
        self.networks = new java.util.ArrayList<>(networkMap.keySet());
    }

    /**
     * Record a network in watchedNetworks so it is restored on next startup.
     */
    private void trackWatchedNetwork(String networkID) {
        if (dataContainer == null) return;
        if (dataContainer.watchedNetworks.add(networkID)) {
            try { saveDataContainer(); } catch (Exception e) {
                log.warn("[NetworkWatch] Could not persist watchedNetworks for {}: {}", networkID, e.getMessage());
            }
        }
    }

    /**
     * Sign this node's own Node object with its own key, returning the signed blob.
     * Same format as CXHELLO signedNode -- used when building seeds.
     * @return Signed node blob, or null on failure
     */
    public byte[] signSelfNode() {
        if (self == null) return null;
        try {
            String nodeJson = serialize("cxJSON1", self);
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
                nodeJson.getBytes(StandardCharsets.UTF_8));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            encryptionProvider.sign(in, out);
            in.close();
            byte[] blob = out.toByteArray();
            out.close();
            return blob;
        } catch (Exception e) {
            log.warn("[ConnectX] Failed to sign self node: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Register a high-value peer (e.g., NMI/EPOCH) in the peer directory.
     * Use this instead of accessing nodeMesh.peerDirectory.hv directly.
     *
     * @param node The trusted node to register
     */
    public void registerHVPeer(Node node) {
        if (nodeMesh == null || node == null || node.cxID == null) return;
        if (isSelfNode(node)) {
            System.err.println("[registerHVPeer] Rejected attempt to register self as HV peer");
            return;
        }
        if (nodeMesh.peerDirectory.hv == null) {
            nodeMesh.peerDirectory.hv = new java.util.concurrent.ConcurrentHashMap<>();
        }
        nodeMesh.peerDirectory.hv.put(node.cxID, node);
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

        // Pre-cache EPOCH's NMI key immediately so events from the seed node can be
        // verified before the async bootstrap file load completes
        if (encryptionProvider instanceof PainlessCryptProvider) {
            PainlessCryptProvider pcp = (PainlessCryptProvider) encryptionProvider;
            if (pcp.nmipubkey != null) {
                pcp.certCache.putIfAbsent(EPOCH_UUID, pcp.nmipubkey);
            }
        }

        // Create and set self node
        Node selfNode = new Node();
        selfNode.cxID = cxID;
        selfNode.publicKey = encryptionProvider.getPublicKey();
        selfNode.addr = listenAddress;
        setSelf(selfNode);

        return cxID;
    }

    /**
     * Set the public bridge address for this node
     * Used when node is behind NAT/firewall and accessible via HTTP bridge
     * @param bridgeProtocol Bridge protocol (e.g., "cxHTTP1")
     * @param bridgeEndpoint Public endpoint (e.g., "<a href="https://cx1.anvildevelopment.us/cx">...</a>")
     */
    public void setPublicBridgeAddress(String bridgeProtocol, String bridgeEndpoint) {
        if (self != null) {
            self.addr = bridgeProtocol + ":" + bridgeEndpoint;
            log.info("[ConnectX] Updated public address: " + self.addr);
            nodeMesh.ownAddresses.add(self.addr);
        }
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

        // Copy signed blob to bootstrap seed location
        File bootstrapSeed = new File(cxRoot, "cxnet-bootstrap.cxn");
        java.nio.file.Files.copy(
            epochSeedFile.toPath(),
            bootstrapSeed.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );

        // Strip signature to read the seed JSON and extract cx.asc if needed
        byte[] blob = java.nio.file.Files.readAllBytes(bootstrapSeed.toPath());
        ByteArrayOutputStream stripped = new ByteArrayOutputStream();
        encryptionProvider.stripSignature(new ByteArrayInputStream(blob), stripped);
        Seed seed = (Seed) deserialize("cxJSON1", stripped.toString(StandardCharsets.UTF_8), Seed.class);

        // Extract EPOCH's public key (cx.asc) if not already present
        if (seed != null && seed.certificates != null && seed.certificates.containsKey(EPOCH_UUID)) {
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
        if (!bootstrapStarted.compareAndSet(false, true)) {
            log.debug("[Bootstrap] Bootstrap already in progress, skipping duplicate call");
            return;
        }
        try {
            if (networkMap.containsKey("CXNET")) {
                log.info("[Bootstrap] CXNET already loaded");
                requestSeedUpdateFromEpoch();
                if (self != null && EPOCH_UUID.equals(self.cxID)) {
                    loadSignedBootstrapSeedAsync("CXNET");
                }
                return;
            }

            log.info("[Bootstrap] CXNET not found, attempting bootstrap...");

            // Step 1: Distribution bootstrap seed blob shipped with software
            File bootstrapFile = new File(cxRoot, "cxnet-bootstrap.cxn");
            if (bootstrapFile.exists()) {
                log.info("[Bootstrap] Found bootstrap seed blob, queuing load...");
                jobQueue.add(new IOJob(new FileInputStream(bootstrapFile), true) {
                    @Override
                    public void doAfter(boolean success) {
                        if (success && o instanceof ByteArrayOutputStream) {
                            applySignedSeed(((ByteArrayOutputStream) o).toByteArray());
                            if (!networkMap.containsKey("CXNET")) {
                                fetchBootstrapSeedAsync();
                            } else {
                                CXNetwork cxnetRef = networkMap.get("CXNET");
                                boolean authoritative = cxnetRef != null
                                        && cxnetRef.configuration != null
                                        && cxnetRef.configuration.backendSet != null
                                        && cxnetRef.configuration.backendSet.contains(getOwnID());
                                if (authoritative) {
                                    // Republish so any networks restored by restoreJoinedNetworks
                                    // (which ran before this IOJob) are included in the distributed
                                    // bootstrap and signedBootstrapSeed. Skip requestSeedUpdateFromEpoch
                                    // -- pulling the hosted seed would overwrite the fresh one.
                                    signAndPublishNetworkSeed("CXNET");
                                } else {
                                    requestSeedUpdateFromEpoch();
                                }
                            }
                        }
                    }
                });
                return;
            }

            // Step 2: Any seed blob in the seeds/ directory
            File seedsDir = new File(cxRoot, "seeds");
            seedsDir.mkdirs();
            File[] seedFiles = seedsDir.listFiles((d, name) -> name.endsWith(".cxn"));
            if (seedFiles != null && seedFiles.length > 0) {
                File latest = seedFiles[0];
                for (File f : seedFiles) {
                    if (f.lastModified() > latest.lastModified()) latest = f;
                }
                final File seedFile = latest;
                log.info("[Bootstrap] Loading seed blob: {}", seedFile.getName());
                jobQueue.add(new IOJob(new FileInputStream(seedFile), true) {
                    @Override
                    public void doAfter(boolean success) {
                        if (success && o instanceof ByteArrayOutputStream) {
                            applySignedSeed(((ByteArrayOutputStream) o).toByteArray());
                            initiateP2PDiscovery();
                            requestSeedUpdateFromEpoch();
                        }
                    }
                });
                return;
            }

            // Step 3: EPOCH is the seed authority -- generate initial seed on first run
            if (self != null && EPOCH_UUID.equals(self.cxID)) {
                log.info("[Bootstrap] This is EPOCH with no local seed -- generating initial bootstrap");
                initEpochBootstrap();
                return;
            }

            // Step 4: Attempt remote fetch from AnvilDevelopment distribution endpoint
            log.info("[Bootstrap] No local seed found, attempting remote fetch...");
            fetchBootstrapSeedAsync();

        } catch (Exception e) {
            log.error("[Bootstrap] Bootstrap attempt failed", e);
        }
    }

    /**
     * Restore previously joined networks from disk.
     * Iterates dataContainer.watchedNetworks and loads each network's seed from
     * networks/<networkID>/seed.cxn, applying it directly without peer contact.
     * CXNET is skipped here as it bootstraps independently via cxnet-bootstrap.cxn.
     */
    private void restoreJoinedNetworks() {
        if (dataContainer == null || dataContainer.watchedNetworks.isEmpty()) return;
        for (String networkID : dataContainer.watchedNetworks) {
            if ("CXNET".equals(networkID)) continue;
            if (networkMap.containsKey(networkID)) continue;
            File seedFile = new File(cxRoot, "networks" + File.separator + networkID + File.separator + "seed.cxn");
            if (!seedFile.exists()) {
                log.warn("[NetworkRestore] Seed file missing for watched network {}, will re-request from peers", networkID);
                joinNetworkFromPeers(networkID);
                continue;
            }
            try {
                byte[] signedBlob = java.nio.file.Files.readAllBytes(seedFile.toPath());
                ByteArrayInputStream in = new ByteArrayInputStream(signedBlob);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                //Trust already existing seeds, required for non CXIK nets 
                encryptionProvider.stripSignature(in, out);
                Seed seed = (Seed) deserialize("cxJSON1", out.toString(StandardCharsets.UTF_8), Seed.class);
                if (seed == null) {
                    log.warn("[NetworkRestore] Failed to deserialize seed for {}, requesting from peers", networkID);
                    joinNetworkFromPeers(networkID);
                    continue;
                }
                applySeed(seed);
                log.info("[NetworkRestore] Restored network {} from persisted seed", networkID);
            } catch (Exception e) {
                log.warn("[NetworkRestore] Could not restore network {}, falling back to peer join: {}", networkID, e.getMessage());
                joinNetworkFromPeers(networkID);
            }
        }
    }

    /**
     * Called when EPOCH starts with no local seed data.
     * Creates CXNET (if not already present), builds a Seed from current state,
     * signs it with EPOCH's key, persists the blob to disk, and sets signedBootstrapSeed.
     * Safe to call on restart -- no-op if a seed blob already exists on disk.
     */
    private void initEpochBootstrap() {
        log.debug("[EpochBootstrap] initEpochBootstrap called");
        try {
            // Ensure CXNET exists in networkMap
            CXNetwork cxnet = networkMap.get("CXNET");
            if (cxnet == null) {
                cxnet = createNetwork("CXNET");
                log.info("[EpochBootstrap] Created CXNET network");
            }

            // Build seed from current state
            Seed seed = new Seed();
            seed.seedID = java.util.UUID.randomUUID().toString();
            seed.timestamp = System.currentTimeMillis();
            seed.networkID = "CXNET";
            seed.addNetwork(cxnet);
            if (self != null) {
                byte[] signedSelfBlob = signSelfNode();
                if (signedSelfBlob != null) {
                    seed.addHvPeer(signedSelfBlob);
                    seed.addPeerFindingNode(signedSelfBlob);
                }
            }

            // Serialize and sign with EPOCH's key
            String seedJson = serialize("cxJSON1", seed);
            ByteArrayInputStream seedInput = new ByteArrayInputStream(seedJson.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream signedOutput = new ByteArrayOutputStream();
            encryptionProvider.sign(seedInput, signedOutput);
            seedInput.close();
            byte[] signedBlob = signedOutput.toByteArray();
            signedOutput.close();

            // Persist: seeds/{seedID}.cxn
            File seedsDir = new File(cxRoot, "seeds");
            seedsDir.mkdirs();
            atomicWriteBytes(new File(seedsDir, seed.seedID + ".cxn"), signedBlob);

            // Persist: cxnet-bootstrap.cxn (distribution copy)
            atomicWriteBytes(new File(cxRoot, "cxnet-bootstrap.cxn"), signedBlob);

            // Record current seed ID in network config
            if (cxnet.configuration.currentSeedID != null) {
                cxnet.configuration.lastSeedID = cxnet.configuration.currentSeedID;
            }
            cxnet.configuration.currentSeedID = seed.seedID;

            // Load into RAM for CXHELLO relay
            signedBootstrapSeed = signedBlob;

            log.info("[EpochBootstrap] Bootstrap seed created and signed: {}", seed.seedID);
        } catch (Exception e) {
            log.error("[EpochBootstrap] Failed to create initial bootstrap seed", e);
        }
    }

    /**
     * Signs and publishes a seed for a network this peer has authority over.
     * Saves the signed blob to seeds/{seedID}.cxn and updates currentSeedID so
     * the SEED_REQUEST handler can serve it automatically.
     *
     * @param networkID Network to publish a seed for
     * @return The signed seed blob, or null on failure
     */
    public byte[] signAndPublishNetworkSeed(String networkID) {
        try {
            CXNetwork network = networkMap.get(networkID);
            if (network == null) {
                log.warn("[NetworkSeed] Network {} not found", networkID);
                return null;
            }
            if (network.configuration == null || network.configuration.backendSet == null
                    || !network.configuration.backendSet.contains(getOwnID())) {
                log.warn("[NetworkSeed] This peer is not authoritative for network {}", networkID);
                return null;
            }

            Seed seed = new Seed();
            seed.seedID = java.util.UUID.randomUUID().toString();
            seed.timestamp = System.currentTimeMillis();
            seed.networkID = networkID;
            // CXNET distribution seed carries all registered networks so bootstrapping peers
            // can discover every network EPOCH knows about in a single exchange.
            if ("CXNET".equals(networkID)) {
                for (CXNetwork net : networkMap.values()) {
                    String netID = net.configuration != null ? net.configuration.netID : null;
                    if (netID != null && !"CXNET".equals(netID)) {
                        // Re-apply the persisted seed for this network so the bundled config
                        // reflects any updates written to disk since the last in-memory load.
                        File netSeedFile = new File(new File(cxRoot, "networks"), netID + File.separator + "seed.cxn");
                        if (netSeedFile.exists()) {
                            try {
                                byte[] blob = java.nio.file.Files.readAllBytes(netSeedFile.toPath());
                                ByteArrayInputStream bais = new ByteArrayInputStream(blob);
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                encryptionProvider.stripSignature(bais, baos);
                                Seed latestSeed = (Seed) deserialize("cxJSON1", baos.toString(StandardCharsets.UTF_8), Seed.class);
                                if (latestSeed != null) {
                                    applySeed(latestSeed);
                                }
                            } catch (Exception e) {
                                log.warn("[NetworkSeed] Could not refresh seed for {} before bundling: {}", netID, e.getMessage());
                            }
                        }
                    }
                    seed.addNetwork(networkMap.getOrDefault(netID != null ? netID : "", net));
                }
            } else {
                seed.addNetwork(network);
            }
            if (self != null) {
                byte[] signedSelfBlob = signSelfNode();
                if (signedSelfBlob != null) {
                    seed.addHvPeer(signedSelfBlob);
                    seed.addPeerFindingNode(signedSelfBlob);
                }
            }

            String seedJson = serialize("cxJSON1", seed);
            ByteArrayInputStream seedInput = new ByteArrayInputStream(seedJson.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream signedOutput = new ByteArrayOutputStream();
            encryptionProvider.sign(seedInput, signedOutput);
            seedInput.close();
            byte[] signedBlob = signedOutput.toByteArray();
            signedOutput.close();

            File seedsDir = new File(cxRoot, "seeds");
            seedsDir.mkdirs();
            atomicWriteBytes(new File(seedsDir, seed.seedID + ".cxn"), signedBlob);

            if ("CXNET".equals(networkID)) {
                atomicWriteBytes(new File(cxRoot, "cxnet-bootstrap.cxn"), signedBlob);
                signedBootstrapSeed = signedBlob;
                log.info("[NetworkSeed] Updated cxnet-bootstrap.cxn and signedBootstrapSeed with latest CXNET seed {}", seed.seedID);
            }

            if (network.configuration.currentSeedID != null) {
                network.configuration.lastSeedID = network.configuration.currentSeedID;
            }
            network.configuration.currentSeedID = seed.seedID;

            log.info("[NetworkSeed] Signed and published seed {} for network {}", seed.seedID, networkID);
            return signedBlob;
        } catch (Exception e) {
            log.error("[NetworkSeed] Failed to publish seed for {}: {}", networkID, e.getMessage());
            return null;
        }
    }

    /**
     * Requests a network seed from peers in order to join a network.
     * Always asks EPOCH first (authoritative), then falls back to all known HV peers.
     * The SEED_RESPONSE handler applies the seed when a response arrives.
     *
     * @param networkID Network to join (e.g., "TESTNET")
     */
    public void joinNetworkFromPeers(String networkID) {
        if (networkMap.containsKey(networkID)) {
            log.info("[NetworkJoin] Already in network {}", networkID);
            return;
        }
        trackWatchedNetwork(networkID);
        String reqJson;
        try {
            reqJson = serialize("cxJSON1", new SeedExchange(networkID));
        } catch (Exception e) {
            log.error("[NetworkJoin] Failed to serialize SEED_REQUEST for {}: {}", networkID, e.getMessage());
            return;
        }

        // Always ask EPOCH first via CXNET mesh -- authoritative seed source, no direct route required
        try {
            buildEvent(EventType.SEED_REQUEST, reqJson.getBytes(StandardCharsets.UTF_8))
                .toNetworkPeer("CXNET", EPOCH_UUID)
                .signData()
                .queue();
            log.info("[NetworkJoin] Sent SEED_REQUEST for {} to EPOCH via CXNET", networkID);
        } catch (Exception e) {
            log.warn("[NetworkJoin] Could not send SEED_REQUEST to EPOCH: {}", e.getMessage());
        }

        // Also ask all known HV peers via CXNET mesh as fallback
        if (nodeMesh == null || nodeMesh.peerDirectory == null
                || nodeMesh.peerDirectory.hv == null || nodeMesh.peerDirectory.hv.isEmpty()) {
            return;
        }
        int sent = 0;
        for (Node peer : nodeMesh.peerDirectory.hv.values()) {
            if (self != null && peer.cxID.equals(self.cxID)) continue;
            if (peer.cxID.equals(EPOCH_UUID)) continue;
            try {
                buildEvent(EventType.SEED_REQUEST, reqJson.getBytes(StandardCharsets.UTF_8))
                    .toNetworkPeer("CXNET", peer.cxID)
                    .signData()
                    .queue();
                sent++;
            } catch (Exception e) {
                log.warn("[NetworkJoin] Failed to queue SEED_REQUEST to {}: {}",
                    peer.cxID.substring(0, 8), e.getMessage());
            }
        }
        if (sent > 0) {
            log.info("[NetworkJoin] Sent SEED_REQUEST for {} to {} peer(s) via CXNET", networkID, sent);
        }
    }

    /**
     * Fetches the bootstrap seed blob from the AnvilDevelopment distribution endpoint on a
     * background thread. On success, calls applySignedSeed() and persists to disk.
     * On failure, sets bootstrapSearch=true so peers can supply via CXHELLO.
     */
    private void fetchBootstrapSeedAsync() {
        Thread fetchThread = new Thread(() -> {
            try {
                log.info("[Bootstrap] Fetching seed from https://anvildevelopment.us/downloads/cxnet-bootstrap.cxn ...");
                java.net.URL url = new java.net.URL("https://anvildevelopment.us/downloads/cxnet-bootstrap.cxn");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "ConnectX/" + ConnectX.class.getPackage().getImplementationVersion());
                int status = conn.getResponseCode();
                if (status == 200) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream in = conn.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                    }
                    byte[] blob = baos.toByteArray();
                    log.info("[Bootstrap] Fetched {} bytes from remote, applying...", blob.length);
                    applySignedSeed(blob);
                    requestSeedUpdateFromEpoch();
                } else {
                    log.warn("[Bootstrap] Remote fetch returned HTTP {}, falling back to CXHELLO", status);
                    bootstrapSearch = true;
                    bootstrapStarted.set(false);
                }
                conn.disconnect();
            } catch (Exception e) {
                log.warn("[Bootstrap] Remote fetch failed: {} - falling back to CXHELLO", e.getMessage());
                bootstrapSearch = true;
                bootstrapStarted.set(false);
            }
        });
        fetchThread.setName("Bootstrap-Fetch-Thread");
        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    /**
     * Asynchronously loads the current seed blob for the given network into RAM
     * so it can be relayed in CXHELLO responses without re-signing.
     */
    private void loadSignedBootstrapSeedAsync(String networkID) {
        try {
            CXNetwork network = networkMap.get(networkID);
            if (network == null || network.configuration == null
                    || network.configuration.currentSeedID == null) return;
            File seedFile = new File(new File(cxRoot, "seeds"),
                    network.configuration.currentSeedID + ".cxn");
            if (!seedFile.exists()) return;
            jobQueue.add(new IOJob(new FileInputStream(seedFile), true) {
                @Override
                public void doAfter(boolean success) {
                    if (success && o instanceof ByteArrayOutputStream) {
                        signedBootstrapSeed = ((ByteArrayOutputStream) o).toByteArray();
                        log.info("[Bootstrap] Signed seed blob loaded for CXHELLO relay");
                    }
                }
            });
        } catch (Exception e) {
            log.error("[Bootstrap] Could not queue seed blob load", e);
        }
    }

    /**
     * Request updated seed from EPOCH (called after successful bootstrap)
     * This allows nodes to get the latest network configuration
     */
    private void requestSeedUpdateFromEpoch() {
        if (self != null && !EPOCH_UUID.equals(self.cxID)) {
            log.info("[Bootstrap] Requesting seed update from EPOCH...");
            requestSeedFromEpoch();
        }
    }

    /**
     * Apply a seed to this ConnectX instance
     * Loads networks, adds peers to directory, caches certificates, and extracts NMI public key
     * @param seed Seed to apply
     * @throws Exception if application fails
     */
    private void applySeed(Seed seed) throws Exception {
        log.info("[Seed] Applying seed {}", seed.seedID);
        log.info("[Seed]   Networks: {}", seed.networks.size());
        log.info("[Seed]   HV Peer Blobs: {}", seed.hvPeerBlobs.size());
        log.info("[Seed]   Certificates: {}", seed.certificates.size());

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
                log.info("[Seed] Extracted NMI public key to cx.asc");
            } else {
                log.info("[Seed] cx.asc already exists, skipping extraction");
            }
        }

        // Ingest hv peers: verify each signed blob against the node's own key before adding
        int peersAdded = 0;
        for (byte[] blob : seed.hvPeerBlobs) {
            if (blob == null) continue;
            try {
                // Strip signature without verification to read the embedded Node (and its publicKey)
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(blob);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                encryptionProvider.stripSignature(bais, baos);
                bais.close();
                String nodeJson = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                baos.close();

                Node peer = (Node) deserialize("cxJSON1", nodeJson, Node.class);
                if (peer == null || peer.cxID == null || peer.publicKey == null) {
                    log.warn("[Seed] Skipping blob -- missing cxID or publicKey after strip");
                    continue;
                }
                if (isSelfNode(peer)) {
                    log.debug("[Seed] Skipped self in hvPeerBlobs: {}", peer.cxID);
                    continue;
                }

                // Cache the peer's public key so we can verify the blob signature
                us.anvildevelopment.cxnet.crypt.pgpainless.PainlessCryptProvider pcp =
                    (us.anvildevelopment.cxnet.crypt.pgpainless.PainlessCryptProvider) encryptionProvider;
                if (!pcp.cacheKeyFromString(peer.cxID, peer.publicKey)) {
                    log.warn("[Seed] Could not cache key for {}, skipping", peer.cxID.substring(0, 8));
                    continue;
                }

                // Verify the blob signature against the peer's own key
                java.io.ByteArrayInputStream verifyIn = new java.io.ByteArrayInputStream(blob);
                java.io.ByteArrayOutputStream verifyOut = new java.io.ByteArrayOutputStream();
                encryptionProvider.verifyAndStrip(verifyIn, verifyOut, peer.cxID);
                verifyIn.close();
                verifyOut.close();

                // Signature valid -- add with signed blob for persistence
                nodeMesh.peerDirectory.addNode(peer, blob, cxRoot);
                peersAdded++;
                log.info("[Seed] Verified and added peer: {}", peer.cxID.substring(0, 8));
            } catch (Exception e) {
                log.warn("[Seed] Rejected peer blob -- signature verification failed: {}", e.getMessage());
            }
        }
        log.info("[Seed] Added {} of {} peer blobs", peersAdded, seed.hvPeerBlobs.size());

        // Import networks using shared registration logic
        for (CXNetwork network : seed.networks) {
            String networkID = network.configuration.netID;
            log.info("[Seed] Importing network: {}", networkID);

            try {
                // Use shared network registration (handles persistence, replay, sync)
                registerNetwork(network);
            } catch (Exception e) {
                log.error("[Seed] Failed to register network {}: {}", networkID, e.getMessage());
            }
        }

        // Cache certificates
        assert seed.certificates != null;
        for (java.util.Map.Entry<String, String> cert : seed.certificates.entrySet()) {
            try {
                encryptionProvider.cacheCert(cert.getKey(), false, false, this);
                log.info("[Seed] Cached certificate: {}", cert.getKey());
            } catch (Exception e) {
                log.error("[Seed] Failed to cache certificate for {}: {}", cert.getKey(), e.getMessage());
            }
        }

        log.info("[Seed] Seed application complete");
    }

    /**
     * Verify and apply a NMI-signed seed blob received from a peer via CXHELLO.
     * The blob MUST be signed by EPOCH (NMI) -- verified against the hardcoded NMI public key.
     * Any peer can relay the blob unchanged; only EPOCH can produce a valid one.
     * @param signedBlob Raw signed seed blob (PGP-signed, not encrypted)
     */
    public void applySignedSeed(byte[] signedBlob) {
        try {
            PainlessCryptProvider pcp = (PainlessCryptProvider) encryptionProvider;
            if (Boolean.getBoolean("cxnet.test.epoch")) {
                pcp.cacheEpochKeyFromFile(cxRoot, EPOCH_UUID);
            }
            pcp.certCache.putIfAbsent(EPOCH_UUID, pcp.nmipubkey);

            // Log first bytes to diagnose PGP format issues
            if (signedBlob != null && signedBlob.length > 0) {
                StringBuilder hexPreview = new StringBuilder();
                for (int i = 0; i < Math.min(16, signedBlob.length); i++) {
                    hexPreview.append(String.format("%02X ", signedBlob[i]));
                }
                log.debug("[Bootstrap] signedBlob length={}, first bytes: {}", signedBlob.length, hexPreview.toString().trim());
            }

            assert signedBlob != null;
            ByteArrayInputStream signedInput = new ByteArrayInputStream(signedBlob);
            ByteArrayOutputStream strippedOutput = new ByteArrayOutputStream();

            boolean verified = encryptionProvider.verifyAndStrip(signedInput, strippedOutput, EPOCH_UUID);
            if (!verified) {
                log.error("[Bootstrap] Rejected seed blob: NMI signature verification failed");
                return;
            }

            String seedJson = strippedOutput.toString(StandardCharsets.UTF_8);
            Seed seed = (Seed) deserialize("cxJSON1", seedJson, Seed.class);
            applySeed(seed);
            if ("CXNET".equals(seed.networkID)) {
                signedBootstrapSeed = signedBlob;
            }
            bootstrapSearch = false;

            // Queue async writes to persist blob on disk
            File seedsDir = new File(cxRoot, "seeds");
            seedsDir.mkdirs();
            final byte[] blobRef = signedBlob;
            final String seedID = seed.seedID;
            final String seedNetworkID = seed.networkID;
            try {
                File seedTarget = new File(seedsDir, seedID + ".cxn");
                File seedTmp = new File(seedsDir, seedID + ".cxn.tmp");
                jobQueue.add(new IOJob(new ByteArrayInputStream(blobRef), new FileOutputStream(seedTmp), true) {
                    @Override public void doAfter(boolean success) {
                        if (success) atomicFinalize(seedTmp, seedTarget);
                    }
                });
                if ("CXNET".equals(seedNetworkID)) {
                    File bsTarget = new File(cxRoot, "cxnet-bootstrap.cxn");
                    File bsTmp = new File(cxRoot, "cxnet-bootstrap.cxn.tmp");
                    jobQueue.add(new IOJob(new ByteArrayInputStream(blobRef), new FileOutputStream(bsTmp), true) {
                        @Override public void doAfter(boolean success) {
                            if (success) atomicFinalize(bsTmp, bsTarget);
                        }
                    });
                } else if (seedNetworkID != null) {
                    File netSeedDir = new File(new File(cxRoot, "networks"), seedNetworkID);
                    netSeedDir.mkdirs();
                    File nsTarget = new File(netSeedDir, "seed.cxn");
                    File nsTmp = new File(netSeedDir, "seed.cxn.tmp");
                    jobQueue.add(new IOJob(new ByteArrayInputStream(blobRef), new FileOutputStream(nsTmp), true) {
                        @Override public void doAfter(boolean success) {
                            if (success) atomicFinalize(nsTmp, nsTarget);
                        }
                    });
                }
            } catch (FileNotFoundException e) {
                log.error("[Bootstrap] Could not queue blob write", e);
            }
            log.info("[Bootstrap] Applied signed seed: {}", seedID);
        } catch (Exception e) {
            log.error("[Bootstrap] Failed to apply signed seed blob", e);
        }
    }

    /**
     * Attempt to apply a signed seed blob by verifying it against the backendSet of the target
     * network (if already loaded) and/or the CXNET backendSet. The sender's identity is irrelevant --
     * only the embedded signature is checked. Returns true if the seed was verified and applied.
     */
    public boolean applyBackendSignedSeed(byte[] signedBlob, String targetNetworkID) {
        java.util.Set<String> trustedKeys = new java.util.LinkedHashSet<>();
        // EPOCH is always trusted as the NMI authority
        trustedKeys.add(EPOCH_UUID);
        // CXNET backendSet members are trusted for all network seeds
        CXNetwork cxnet = networkMap.get("CXNET");
        if (cxnet != null && cxnet.configuration != null && cxnet.configuration.backendSet != null) {
            trustedKeys.addAll(cxnet.configuration.backendSet);
        }
        // If the target network is already loaded, its backendSet is also trusted
        CXNetwork targetNet = networkMap.get(targetNetworkID);
        if (targetNet != null && targetNet.configuration != null && targetNet.configuration.backendSet != null) {
            trustedKeys.addAll(targetNet.configuration.backendSet);
        }
        for (String trustedID : trustedKeys) {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(signedBlob);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean verified = encryptionProvider.verifyAndStrip(in, out, trustedID);
                if (verified) {
                    String seedJson = out.toString(StandardCharsets.UTF_8);
                    Seed seed = (Seed) deserialize("cxJSON1", seedJson, Seed.class);
                    if (seed != null) {
                        applySeed(seed);
                        log.info("[SeedVerify] Applied seed for {} verified by backendSet member {}",
                                targetNetworkID, trustedID.substring(0, 8));
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("[SeedVerify] Key {} did not verify blob for {}: {}", trustedID.substring(0, 8), targetNetworkID, e.getMessage());
            }
        }
        log.error("[SeedVerify] No backendSet key verified seed blob for {} -- manual import required", targetNetworkID);
        return false;
    }

    /**
     * Import a creator-signed network seed on EPOCH's behalf during NETEPOCH processing.
     * CXIK already authenticated the request; verification here is best-effort against the creator's
     * cached key and falls back to signature-strip if the cert is unavailable.
     */
    public void importTrustedNetworkSeed(byte[] signedBlob, String creatorCxID) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(signedBlob);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean verified = false;
            try {
                verified = encryptionProvider.verifyAndStrip(bais, baos, creatorCxID);
            } catch (Exception ignored) {}
            if (!verified) {
                bais = new ByteArrayInputStream(signedBlob);
                baos = new ByteArrayOutputStream();
                encryptionProvider.stripSignature(bais, baos);
            }
            String seedJson = baos.toString(StandardCharsets.UTF_8);
            Seed seed = (Seed) deserialize("cxJSON1", seedJson, Seed.class);
            if (seed == null) {
                log.error("[NETEPOCH] Failed to parse creator seed from {}", creatorCxID.substring(0, 8));
                return;
            }
            applySeed(seed);
            log.info("[NETEPOCH] Imported trusted network seed {} from {}", seed.seedID, creatorCxID.substring(0, 8));
        } catch (Exception e) {
            log.error("[NETEPOCH] importTrustedNetworkSeed failed: {}", e.getMessage());
        }
    }

    /**
     * Request CXNET seed from EPOCH NMI via SEED_REQUEST event
     * First introduces this node to EPOCH via NewNode, then requests seed
     * Uses CXS scope for direct node-to-node communication during bootstrap
     */
    private void requestSeedFromEpoch() {
        try {
            log.info("[Bootstrap] Contacting EPOCH at " + EPOCH_BRIDGE_ADDRESS);

            // Step 1: Create hardcoded EPOCH node with bridge address for bootstrap
            Node epochNode = new Node();
            epochNode.cxID = EPOCH_UUID;
            epochNode.addr = EPOCH_BRIDGE_ADDRESS;
            //TODO evaluate better options
            epochNode.publicKey = encryptionProvider.getNmiPublicKey();

            // Add EPOCH to peer directory so we can route to it
            try {
                nodeMesh.peerDirectory.addNode(epochNode);
                log.info("[Bootstrap] Added EPOCH to peer directory");
            } catch (SecurityException e) {
                log.info("[Bootstrap] EPOCH already in peer directory");
            }

            // Step 2: Introduce ourselves to EPOCH via NewNode event
            // This allows EPOCH to cache our certificate and encrypt responses for us
            if (self != null && self.publicKey != null) {
                log.info("[Bootstrap] Introducing ourselves to EPOCH...");

                // Serialize our node information as the event payload
                String selfJson = serialize("cxJSON1", self);

                // Send NewNode using EventBuilder pattern with signature
                String[] bridgeParts = EPOCH_BRIDGE_ADDRESS.split(":", 2);
                buildEvent(EventType.NewNode, selfJson.getBytes(StandardCharsets.UTF_8))
                    .toPeer(EPOCH_UUID)
                    .viaBridge(bridgeParts[0], bridgeParts[1])
                    .signData()
                    .queue();

                log.info("[Bootstrap] NewNode introduction queued");
            }

            // Step 3: Send SEED_REQUEST to EPOCH
            log.info("[Bootstrap] Requesting CXNET seed...");

            // Send SEED_REQUEST using EventBuilder pattern with signature
            String[] bridgeParts2 = EPOCH_BRIDGE_ADDRESS.split(":", 2);
            buildEvent(EventType.SEED_REQUEST,
                serialize("cxJSON1", new SeedExchange("CXNET")).getBytes(StandardCharsets.UTF_8))
                .toPeer(EPOCH_UUID)
                .viaBridge(bridgeParts2[0], bridgeParts2[1])
                .signData()
                .queue();

            log.info("[Bootstrap] SEED_REQUEST queued for EPOCH");
            log.info("[Bootstrap] Waiting for SEED_RESPONSE via HTTP bridge...");

        } catch (Exception e) {
            log.error("[Bootstrap] Failed to request seed from EPOCH ", e);
        }
    }

    /**
     * Initiate P2P discovery with all peers from seed
     * Sends NewNode and PeerFinding to establish crypto and discover more peers
     * This allows bootstrap to work even when EPOCH is offline
     */
    private void initiateP2PDiscovery() {
        try {
            log.info("[P2P Discovery] Contacting {} peers from seed...", nodeMesh.peerDirectory.hv.size());

            int contactAttempts = 0;
            for (Node peer : nodeMesh.peerDirectory.hv.values()) {
                if (peer.cxID == null || peer.cxID.equals(getOwnID())) {
                    continue; // Skip invalid or self
                }

                try {
                    // Send NewNode with SIGNED Node blob (receiver will save original signed blob for relay)
                    log.info("[P2P Discovery] Sending NewNode to {}", peer.cxID.substring(0, 8));
                    String selfJson = serialize("cxJSON1", self);
                    buildEvent(EventType.NewNode, selfJson.getBytes(StandardCharsets.UTF_8))
                        .signData()  // Sign Node JSON to create signed blob (preserves sender signature)
                        .toPeer(peer.cxID)
                        .queue();

                    // Send PeerFinding request to discover more peers
                    log.info("[P2P Discovery] Sending PeerFinding to {}", peer.cxID.substring(0, 8));
                    PeerFinding peerFindingReq =
                        new PeerFinding();
                    peerFindingReq.t = "request";
                    peerFindingReq.network = "CXNET"; // Request CXNET peers
                    String peerFindingJson = serialize("cxJSON1", peerFindingReq);
                    buildEvent(EventType.PeerFinding, peerFindingJson.getBytes(StandardCharsets.UTF_8))
                        .toPeer(peer.cxID)
                        .signData()
                        .queue();

                    contactAttempts++;

                } catch (Exception e) {
                    log.error("[P2P Discovery] Failed to contact {}: {}", peer.cxID.substring(0, 8), e.getMessage());
                }
            }

            log.info("[P2P Discovery] Initial discovery requests queued to {} peers", contactAttempts);
        } catch (Exception e) {
            log.error("[P2P Discovery] Discovery failed ", e);
        }
    }

    /**
     * Initialize and start the P2P network layer
     * Automatically attempts to bootstrap CXNET if not already loaded
     * @param port Port number for incoming connections
     * @throws IOException if network initialization fails
     */
    public void connect(int port) throws IOException {
        this.listeningPort = port;
        warnExistingRecoveryMarkers();
        this.streamManager = new us.anvildevelopment.cxnet.network.stream.CXStreamManager(this);
        OutConnectionController outController =
            new OutConnectionController(this);
        nodeMesh = NodeMesh.initializeNetwork(this, port, outController);
        // Initialize nodeMesh.peerDirectory.peers for signed node persistence
        nodeMesh.peerDirectory.peers = nodemesh;

        // Attempt automatic CXNET bootstrap after network layer is ready
        attemptCXNETBootstrap();

        // Restore any non-CXNET networks this node previously joined
        restoreJoinedNetworks();

        // If authoritative for CXNET, republish so that any networks loaded above by
        // restoreJoinedNetworks are folded into signedBootstrapSeed and cxnet-bootstrap.cxn.
        CXNetwork cxnetForRepublish = networkMap.get("CXNET");
        if (cxnetForRepublish != null && cxnetForRepublish.configuration != null
                && cxnetForRepublish.configuration.backendSet != null
                && cxnetForRepublish.configuration.backendSet.contains(getOwnID())) {
            signAndPublishNetworkSeed("CXNET");
        }

        // Initialize LAN scanner for local peer discovery.
        // Runs once on startup (after jitter), then every 15 minutes.
        // After steady-state is reached, Global Scanner will be started (not yet implemented).
        Thread lanScanThread = new Thread(() -> {
            try {
                long jitter = (long) (Math.random() * 10000);
                Thread.sleep(10000 + jitter);

                boolean globalScannerStarted = false;

                while (true) {
                    try {
                        if (nodeMesh != null && nodeMesh.in != null && nodeMesh.in.serverSocket != null &&
                                nodeMesh.in.serverSocket.isBound() && !nodeMesh.in.serverSocket.isClosed()) {
                            new LANScanner(this, port).scanNetwork();
                            if (!globalScannerStarted) {
                                globalScannerStarted = true;
                                // TODO: startGlobalScanner();
                            }
                        } else {
                            log.warn("[LAN Scanner] Socket not ready, skipping scan");
                        }
                    } catch (Exception e) {
                        log.error("[LAN Scanner] Scan failed: ", e);
                    }
                    Thread.sleep(15 * 60 * 1000L);
                }
            } catch (InterruptedException e) {
                log.info("[LAN Scanner] Thread interrupted, stopping periodic scans");
            } catch (Exception e) {
                log.error("[LAN Scanner] Fatal error: ", e);
            }
        });
        lanScanThread.setName("LAN-Scanner-Periodic");
        lanScanThread.setDaemon(true);
        lanScanThread.start();

        // Start persistence thread for periodic saves and peer discovery
        Thread persistenceThread = new Thread(() -> {
            try {
                // Wait for CXHELLO handshake to complete before starting persistence
                Thread.sleep(5000);

                log.info("[Persistence] Starting periodic save and discovery thread");
                log.info("[Persistence] - Blockchain saves: every 30 seconds");
                log.info("[Persistence] - Peer discovery: backoff {}s/{}s then every {}s",
                    NodeConfig.peerDiscoveryBackoff1Ms / 1000,
                    NodeConfig.peerDiscoveryBackoff2Ms / 1000,
                    NodeConfig.peerDiscoverySteadyMs   / 1000);

                int  discoveryRun     = 0;
                long nextDiscoveryAt  = System.currentTimeMillis() + NodeConfig.peerDiscoveryBackoff1Ms;

                while (true) {
                    try {
                        // Save blockchain data (current blocks + metadata) for all networks
                        for (Map.Entry<String, CXNetwork> entry : networkMap.entrySet()) {
                            String networkID = entry.getKey();
                            CXNetwork network = entry.getValue();

                            if (network == null) continue;

                            // Save c1 (Administrative chain)
                            if (network.c1 != null && network.c1.current != null) {
                                try {
                                    blockchainPersistence.saveBlock(networkID, network.c1.chainID, network.c1.current);
                                    blockchainPersistence.saveChainMetadata(network.c1, networkID);
                                } catch (Exception e) {
                                    log.error("[Persistence] Failed to save c1 for {}: {}", networkID, e.getMessage());
                                }
                            }

                            // Save c2 (Resources chain)
                            if (network.c2 != null && network.c2.current != null) {
                                try {
                                    blockchainPersistence.saveBlock(networkID, network.c2.chainID, network.c2.current);
                                    blockchainPersistence.saveChainMetadata(network.c2, networkID);
                                } catch (Exception e) {
                                    log.error("[Persistence] Failed to save c2 for {}: {}", networkID, e.getMessage());
                                }
                            }

                            // Save c3 (Events chain)
                            if (network.c3 != null && network.c3.current != null) {
                                try {
                                    blockchainPersistence.saveBlock(networkID, network.c3.chainID, network.c3.current);
                                    blockchainPersistence.saveChainMetadata(network.c3, networkID);
                                } catch (Exception e) {
                                    log.error("[Persistence] Failed to save c3 for {}: {}", networkID, e.getMessage());
                                }
                            }
                        }

                        // TODO: Persist PeerDirectory
                        try {
                            saveDataContainer();
                        } catch (Exception e) {
                            log.error("[Persistence] Failed to save DataContainer: {}", e.getMessage());
                        }

                        if (System.currentTimeMillis() >= nextDiscoveryAt) {
                            discoveryRun++;
                            long nextDelay = discoveryRun == 1 ? NodeConfig.peerDiscoveryBackoff2Ms
                                                               : NodeConfig.peerDiscoverySteadyMs;
                            nextDiscoveryAt = System.currentTimeMillis() + nextDelay;

                            // Send CXHELLO to waiting addresses via lowLevel builder
                            log.info("[ConnectX] Sending CXHELLO to {} addresses", dataContainer.waitingAddresses.size());
                            for (String s : dataContainer.waitingAddresses) {
                                if (!isValidPeerAddress(s) || isSelfAddress(s)) {
                                    log.info("[ConnectX] Skipped CXHELLO target (self or invalid): {}", s);
                                    continue;
                                }
                                try {
                                    byte[] signedNodeBlob = signSelfNode();
                                    if (signedNodeBlob == null) continue;
                                    CXHello helloPayload = new CXHello(getOwnID(), listeningPort, signedNodeBlob,
                                        getSelf() != null ? getSelf().addr : null);
                                    String payloadJson = ConnectX.serialize("cxJSON1", helloPayload);
                                    buildEvent(EventType.CXHELLO, payloadJson.getBytes(StandardCharsets.UTF_8))
                                        .lowLevel(s)
                                        .signData()
                                        .queue();
                                    log.info("[ConnectX] Queued CXHELLO to {}", s);
                                } catch (Exception e) {
                                    log.warn("[ConnectX] Failed to queue CXHELLO to {}: {}", s, e.getMessage());
                                }
                            }
                        //Clear after
                            //TODO evaluate thread safety
                            dataContainer.waitingAddresses.clear();

                            try {
                                log.info("[Peer Discovery] Sending PeerFinding requests to known peers...");

                                // Send PeerFinding to a subset of known peers to discover new nodes/bridges
                                int peersSent = 0;
                                int maxPeersToContact = 5; // Contact up to 5 peers per discovery cycle

                                if (nodeMesh.peerDirectory.hv != null && !nodeMesh.peerDirectory.hv.isEmpty()) {
                                    for (Node peer : nodeMesh.peerDirectory.hv.values()) {
                                        if (peersSent >= maxPeersToContact) break;

                                        if (peer.cxID == null || peer.cxID.equals(getOwnID())) {
                                            continue; // Skip invalid or self
                                        }

                                        try {
                                            // Create PeerFinding request
                                            PeerFinding peerFindingReq =
                                                new PeerFinding();
                                            peerFindingReq.t = "request";
                                            peerFindingReq.network = "CXNET"; // Request CXNET peers
                                            String peerFindingJson = serialize("cxJSON1", peerFindingReq);

                                            buildEvent(EventType.PeerFinding, peerFindingJson.getBytes("UTF-8"))
                                                .toPeer(peer.cxID)
                                                .signData()
                                                .queue();

                                            peersSent++;
                                        } catch (Exception e) {
                                            log.error("[Peer Discovery] Failed to send to {}: {}", peer.cxID.length() >= 8 ? peer.cxID.substring(0, 8) : peer.cxID, e.getMessage());
                                        }
                                    }

                                    if (peersSent > 0) {
                                        log.info("[Peer Discovery] Sent PeerFinding requests to {} peers", peersSent);
                                    } else {
                                        log.info("[Peer Discovery] No peers available for discovery");
                                    }
                                }
                            } catch (Exception e) {
                                log.error("[Peer Discovery] Error during peer discovery: {}", e.getMessage());
                            }
                        }

                    } catch (Exception e) {
                        log.error("[Persistence] Error during periodic save: {}", e.getMessage());
                    }

                    // Wait 30 seconds before next cycle
                    Thread.sleep(30000);
                }
            } catch (InterruptedException e) {
                log.info("[Persistence] Thread interrupted, stopping periodic saves");
            } catch (Exception e) {
                log.error("[Persistence] Fatal error in persistence thread: {}", e.getMessage());
            }
        });
        persistenceThread.setName("Persistence-Discovery-Thread");
        persistenceThread.setDaemon(true);
        persistenceThread.start();
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
        if (networkID == null || networkID.equals("*") || !networkID.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid network ID: '" + networkID + "'");
        }
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
        network.c1 = new NetworkRecord(networkID, 1L);
        network.c1.networkID = networkID;
        network.c1.chainID = 1L;
        network.c1.current = new NetworkBlock(0L, 1L);
        network.c1.blockMap.put(0L, network.c1.current);

        network.c2 = new NetworkRecord(networkID, 2L);
        network.c2.networkID = networkID;
        network.c2.chainID = 2L;
        network.c2.current = new NetworkBlock(0L, 2L);
        network.c2.blockMap.put(0L, network.c2.current);

        network.c3 = new NetworkRecord(networkID, 3L);
        network.c3.networkID = networkID;
        network.c3.chainID = 3L;
        network.c3.current = new NetworkBlock(0L, 3L);
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
        //TODO this seems to be not needed anymore
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
        refreshSelfNetworks();
        trackWatchedNetwork(networkID);

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

            log.info("[Blockchain] Network {} persisted to disk", networkID);
        } catch (Exception e) {
            log.error("[Blockchain] Failed to persist network {}: {}", networkID, e.getMessage());
        }

        // If CXNET is available and this node is not EPOCH, register the network with CXNET NMI
        if (!EPOCH_UUID.equals(self.cxID) && networkMap.containsKey("CXNET")) {
            submitNetworkToEpoch(network);
        }

        return network;
    }

    /**
     * Check whether a network name is already registered with the CXNET backend set.
     * Sends CHECK_NETWORK_NAME to all CXNET backends via CXB scope.
     * qd deduplication ensures only one backend processes and responds.
     * The callback is invoked from the NodeMesh event thread -- do not block inside it.
     *
     * @param name     the network name to check
     * @param callback receives the full NetworkNameCheck response (taken, message, etc.)
     */
    public void checkNetworkName(String name,
            Consumer<us.anvildevelopment.cxnet.network.events.NetworkNameCheck> callback) throws Exception {
        String requestId = java.util.UUID.randomUUID().toString();
        us.anvildevelopment.cxnet.network.events.NetworkNameCheck payload =
                new us.anvildevelopment.cxnet.network.events.NetworkNameCheck();
        payload.networkName = name;
        payload.requestId = requestId;

        pendingNameChecks.put(requestId, callback);

        buildEvent(us.anvildevelopment.cxnet.network.events.EventType.CHECK_NETWORK_NAME,
                serialize("cxJSON1", payload).getBytes(StandardCharsets.UTF_8))
                .toBackends("CXNET", requestId)
                .signData()
                .queue();
    }

    /**
     * Sign the seed for a locally created network and send a NETEPOCH registration request
     * to the CXNET backend set. The CXIK must have been obtained from the CXNET NMI beforehand.
     * On approval, CXNET NMI responds with a NETEPOCH_RESPONSE carrying an NMI-signed seed.
     *
     * @param network the locally created network
     */
    public void submitNetworkToEpoch(CXNetwork network) {
        String networkID = network.configuration.netID;
        String cxik = network.configuration.pendingCXIK;
        if (cxik == null || cxik.isBlank()) {
            log.warn("[NETEPOCH] No CXIK set on network {} -- skipping EPOCH registration", networkID);
            return;
        }
        try {
            // Sign the seed for this network
            byte[] signedSeedBlob = signAndPublishNetworkSeed(networkID);
            if (signedSeedBlob == null) {
                log.error("[NETEPOCH] Failed to sign seed for network {} -- cannot submit to EPOCH", networkID);
                return;
            }

            NetworkEpoch payload = new NetworkEpoch();
            payload.networkID = networkID;
            payload.cxik = cxik;
            payload.signedSeedBlob = signedSeedBlob;

            String payloadJson = serialize("cxJSON1", payload);

            buildEvent(EventType.NETEPOCH, payloadJson.getBytes(StandardCharsets.UTF_8))
                    .addRecipient(EPOCH_UUID)
                    .encrypt()
                    .toPeer(EPOCH_UUID)
                    .queue();

            // Clear the key from config so it is not persisted or reused
            network.configuration.pendingCXIK = null;

            log.info("[NETEPOCH] Registration request queued for network {}", networkID);
        } catch (Exception e) {
            log.error("[NETEPOCH] Failed to submit network {} to EPOCH: {}", networkID, e.getMessage());
        }
    }

    /**
     * Issue a CXIK from this node's DataContainer (CXNET NMI only).
     * The label is stored for audit purposes. The key must be delivered to the requesting
     * node out-of-band before they call createNetwork().
     *
     * @param label audit label (e.g. requester node ID or description)
     * @return the generated CXIK, or null if this node is not EPOCH
     */
    public String issueCXIK(String label) {
        if (!EPOCH_UUID.equals(self != null ? self.cxID : null)) {
            log.warn("[CXIK] issueCXIK called on non-EPOCH node - ignored");
            return null;
        }
        String key = dataContainer.issueCXIK(label);
        try {
            saveDataContainer();
        } catch (Exception e) {
            log.error("[CXIK] Failed to persist DataContainer after issuing CXIK: {}", e.getMessage());
        }
        log.info("[CXIK] Issued key for label: {}", label);
        return key;
    }

    /**
     * Activate Zero Trust mode for a network (NMI-only, IRREVERSIBLE)
     *
     * This method transitions a network from centralized (NMI-controlled) to fully decentralized (zero trust).
     * After activation:
     * - NMI loses all special permissions
     * - Network becomes fully peer-governed
     * - Blockchain consensus switches to multi-peer voting
     * - Operation is PERMANENT and CANNOT be reversed
     *
     * @param networkID Network to activate zero trust mode for
     * @throws IllegalAccessException if caller is not NMI or network doesn't exist
     * @throws Exception if event creation, signing, or distribution fails
     */
    public void startZeroTrust(String networkID) throws IllegalAccessException, Exception {
        // Verify node is initialized
        if (self == null || self.cxID == null) {
            throw new IllegalAccessException("Node must be initialized before activating zero trust");
        }

        // Get network
        CXNetwork network = networkMap.get(networkID);
        if (network == null) {
            throw new IllegalAccessException("Network " + networkID + " not found");
        }

        // Verify caller is NMI (first backend)
        if (network.configuration == null || network.configuration.backendSet == null ||
            network.configuration.backendSet.isEmpty() ||
            !network.configuration.backendSet.get(0).equals(self.cxID)) {
            throw new IllegalAccessException("Only NMI can activate zero trust mode");
        }

        // Verify network is not already in zero trust mode
        if (network.zT) {
            throw new IllegalAccessException("Network " + networkID + " is already in zero trust mode");
        }

        log.info("[Zero Trust] Activating zero trust mode for network {}", networkID);
        log.info("[Zero Trust] WARNING: This operation is IRREVERSIBLE");

        // Set zT flag in network configuration
        network.zT = true;

        ZeroTrustActivation payload =
            new ZeroTrustActivation(
                networkID, System.currentTimeMillis(), network.configuration.nmiPub);

        String payloadJson = serialize("cxJSON1", payload);
        log.info("[Zero Trust] Created ZERO_TRUST_ACTIVATION event payload");

        // Build and queue ZERO_TRUST_ACTIVATION event
        // Record to c1 (Admin chain) and distribute to all network participants
        buildEvent(EventType.ZERO_TRUST_ACTIVATION, payloadJson.getBytes(StandardCharsets.UTF_8))
            .withRecordFlag(true)  // Enable automatic recording
            .toNetwork(networkID, network.networkDictionary.c1)  // Record to c1 (Admin chain)
            .queue();

        log.info("[Zero Trust] ZERO_TRUST_ACTIVATION event queued for network {}", networkID);
        log.info("[Zero Trust] NMI permissions will be blocked after event is processed");
        log.info("[Zero Trust] Network is now in zero trust mode");

        // Persist updated network configuration
        try {
            blockchainPersistence.saveChainMetadata(network.c1, networkID);
            blockchainPersistence.saveChainMetadata(network.c2, networkID);
            blockchainPersistence.saveChainMetadata(network.c3, networkID);
            log.info("[Zero Trust] Network configuration persisted");
        } catch (Exception e) {
            log.error("[Zero Trust] Failed to persist network configuration: {}", e.getMessage());
        }
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
        NetworkContainer nc = new NetworkContainer();
        nc.se = "cxJSON1";
        nc.s = false; // Not E2E encrypted
        nc.iD = getOwnID(); // Sender's cxID
        if (NodeConfig.revealVersion) nc.v = NodeConfig.cxV;

        // Sign the inner CXNetwork object
        byte[] signedNetwork = signObject(network, CXNetwork.class, nc.se).toByteArray();
        nc.e = signedNetwork;

        // Sign the outer NetworkContainer
        byte[] signedContainer = signObject(nc, NetworkContainer.class, nc.se).toByteArray();

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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Step 1: Decrypt outer NetworkContainer (verifies outer signature)
        try (FileInputStream fis = new FileInputStream(importFile)) {
            encryptionProvider.decrypt(fis, baos);
        }
        String networkContainer = baos.toString(StandardCharsets.UTF_8);

        // Step 2: Deserialize NetworkContainer from decrypted JSON
        NetworkContainer nc =
            (NetworkContainer) deserialize(
                "cxJSON1", networkContainer, NetworkContainer.class);

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
        CXNetwork network = (CXNetwork) deserialize(nc.se, networkBaos.toString(StandardCharsets.UTF_8), CXNetwork.class);

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

        // Register network (shared logic for both import and seed application)
        registerNetwork(network);

        return network;
    }

    /**
     * Register a network in this ConnectX instance
     * Handles blockchain persistence, replay, and sync for both network creation and import
     * Shared logic used by createNetwork(), importNetwork(), and applySeed()
     *
     * @param network Network to register
     * @throws Exception if registration fails
     */
    private void registerNetwork(CXNetwork network) throws Exception {
        String networkID = network.configuration.netID;
        if (networkID == null || networkID.equals("*") || !networkID.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid network ID in seed: '" + networkID + "'");
        }

        // Add to network map
        networkMap.put(networkID, network);
        refreshSelfNetworks();
        trackWatchedNetwork(networkID);

        // Try to load persisted blockchain data from disk
        try {
            if (blockchainPersistence.exists(networkID)) {
                log.info("[Blockchain] Loading persisted chains for network {}", networkID);

                // Load chains (lazy loading - only current blocks initially)
                NetworkRecord c1 = blockchainPersistence.loadChain(networkID, 1L, false);
                NetworkRecord c2 = blockchainPersistence.loadChain(networkID, 2L, false);
                NetworkRecord c3 = blockchainPersistence.loadChain(networkID, 3L, false);

                // Apply loaded chains if they exist
                if (c1 != null) {
                    network.c1 = c1;
                    log.info("[Blockchain] Loaded chain c1 ({} blocks in memory)", c1.blockMap.size());
                }
                if (c2 != null) {
                    network.c2 = c2;
                    log.info("[Blockchain] Loaded chain c2 ({} blocks in memory)", c2.blockMap.size());
                }
                if (c3 != null) {
                    network.c3 = c3;
                    log.info("[Blockchain] Loaded chain c3 ({} blocks in memory)", c3.blockMap.size());
                }
            } else {
                log.info("[Blockchain] No persisted blockchain found for network {}", networkID);
                log.info("[Blockchain] Persisting genesis blocks from network configuration...");

                // Persist the genesis blocks that came with the network configuration
                // This ensures peers have blockchain data on disk for future restarts
                try {
                    if (network.c1 != null && network.c1.current != null) {
                        blockchainPersistence.saveBlock(networkID, 1L, network.c1.current);
                        blockchainPersistence.saveChainMetadata(network.c1, networkID);
                    }
                    if (network.c2 != null && network.c2.current != null) {
                        blockchainPersistence.saveBlock(networkID, 2L, network.c2.current);
                        blockchainPersistence.saveChainMetadata(network.c2, networkID);
                    }
                    if (network.c3 != null && network.c3.current != null) {
                        blockchainPersistence.saveBlock(networkID, 3L, network.c3.current);
                        blockchainPersistence.saveChainMetadata(network.c3, networkID);
                    }
                    log.info("[Blockchain] Genesis blocks persisted to disk");
                } catch (Exception saveEx) {
                    log.error("[Blockchain] Failed to persist genesis blocks: {}", saveEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Blockchain] Failed to load persisted chains for {}: {}", networkID, e.getMessage());
            File networkBlockchainDir = new File(new File(cxRoot, "blockchain"), networkID);
            createRecoveryMarker(networkBlockchainDir,
                "One or more chain files for network " + networkID + " could not be read and may be corrupt.\n" +
                "Saves for this network are BLOCKED until this file is removed.\n" +
                "Option A - Manual fix: Shut down the node, fix or replace the corrupt chain-*.json / block-*.json files,\n" +
                "  delete this file, then restart.\n" +
                "Option B - Self-recovery: Delete this file while the node is running and ConnectX will attempt rebuild from peers.\n");
            // Not fatal - network can still function with in-memory chains
        }

        // Replay blockchain to restore state after loading from disk
        replayBlockchain(network);

        // Request chain status from NMI to trigger automatic blockchain sync
        requestChainStatusFromNMI(network);
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

    public boolean checkGlobalPermission(String cxID, String permission) {
        assert !cxID.contains("SYSTEM");
        CXNetwork cxnet = getNetwork("CXNET");
        if (cxnet == null) return false;
        return cxnet.checkNetworkPermission(cxID, permission);
    }

    /**
     * Check if a node is blocked at CXNET level (global block)
     * CXNET-level blocks reject ALL transmissions from the node across all networks
     * @param nodeID Node UUID to check
     * @return true if node is blocked at CXNET level
     */
    public boolean isCXNETBlocked(String nodeID) {
        return cxnetBlockedNodes.containsKey(nodeID);
    }

    /**
     * Block a node at CXNET level (global block across all networks)
     * This should only be called when processing a BLOCK_NODE event where network="CXNET"
     * @param nodeID Node UUID to block
     * @param reason Block reason/metadata
     */
    public void blockNodeCXNET(String nodeID, String reason) {
        cxnetBlockedNodes.put(nodeID, reason);
        log.info("[CXNET] Blocked node globally: {} (reason: {})", nodeID, reason);
    }

    /**
     * Unblock a node from CXNET level
     * This should only be called when processing an UNBLOCK_NODE event where network="CXNET"
     * @param nodeID Node UUID to unblock
     */
    public void unblockNodeCXNET(String nodeID) {
        String reason = cxnetBlockedNodes.remove(nodeID);
        if (reason != null) {
            log.info("[CXNET] Unblocked node globally: " + nodeID + " (was blocked for: " + reason + ")");
        }
    }


    public boolean addPlugin(CXPlugin cxp) {
        try {
            checkSafety(cxp.serviceName);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (plugins.containsKey(cxp.serviceName)) return false;
        plugins.put(cxp.serviceName, cxp);
        if (cxp instanceof us.anvildevelopment.cxnet.api.CXStreamPlugin) {
            ((us.anvildevelopment.cxnet.api.CXStreamPlugin) cxp).initialize(this, streamManager);
        }
        return true;
    }
    public boolean sendPluginEvent(InputBundle ib, String eventType) {
        if (!plugins.containsKey(eventType)) return false;
        CXPlugin plugin = plugins.get(eventType);
        try {
            // Resolve origin sender: prefer oCXID (survives relay), fall back to transmitter ID
            String senderCxID = null;
            if (ib.ne != null && ib.ne.p != null && ib.ne.p.oCXID != null) {
                senderCxID = ib.ne.p.oCXID;
            } else if (ib.nc != null && ib.nc.iD != null) {
                senderCxID = ib.nc.iD;
            }

            switch (plugin.dataLevel != null ? plugin.dataLevel : DataLevel.NETWORK_EVENT) {
                case INPUT_BUNDLE:
                    return plugin.handleEvent(ib, senderCxID);
                case OBJECT:
                    if (plugin.type == null || ib.verifiedObjectBytes == null) return false;
                    String se = (ib.nc != null && ib.nc.se != null) ? ib.nc.se : "cxJSON1";
                    if (!ib.readyObject(plugin.type, se, this)) return false;
                    return plugin.handleEvent(ib.object, senderCxID);
                case NETWORK_EVENT:
                default:
                    return plugin.handleEvent(ib.ne, senderCxID);
            }
        } catch (Exception e) {
            log.error("[Plugin] Error dispatching event to plugin '{}': {}", eventType, e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // CXApp registry
    // -------------------------------------------------------------------------

    /**
     * Register a CXAppServer on this node. Builds the reflection cache immediately.
     * Incoming APP_REQUEST events matching this appID will be dispatched here.
     */
    public void registerApp(us.anvildevelopment.cxnet.app.CXAppServer server) {
        server.buildCache();
        appServers.put(server.getAppID(), server);
        log.info("[CXApp] Registered server app '{}'", server.getAppID());
    }

    /**
     * Register a CXAppClient on this node. Builds the reflection cache immediately.
     * Incoming APP_RESPONSE events matching this appID will be dispatched here.
     */
    public void registerApp(us.anvildevelopment.cxnet.app.CXAppClient client) {
        client.buildCache();
        appClients.put(client.getAppID(), client);
        log.info("[CXApp] Registered client app '{}'", client.getAppID());
    }

    public us.anvildevelopment.cxnet.app.CXAppServer getAppServer(String appID) {
        return appServers.get(appID);
    }

    public us.anvildevelopment.cxnet.app.CXAppClient getAppClient(String appID) {
        return appClients.get(appID);
    }

    // -------------------------------------------------------------------------
    // CXApp machine-scoped permissions
    // -------------------------------------------------------------------------

    /**
     * Grant a CXID permission to invoke protected CXApp operations.
     * Persisted immediately to DataContainer.
     *
     * @param cxid       the peer CXID to grant access to
     * @param permission the permission string (e.g. "rprox.admin")
     * @param weight     weight for relational delegation -- higher weight can grant lower weights
     */
    public void grantCXIDPermission(String cxid, String permission, int weight) throws Exception {
        us.anvildevelopment.util.tools.permissions.BasicEntry entry =
                new us.anvildevelopment.util.tools.permissions.BasicEntry(permission, true, weight);
        // Using permissionSet directly -- Util1 BasicPermissionContainer.addEntry() has two bugs:
        // 1. Private addEntry(id, e) guards on permissionSet.containsKey(id), silently dropping grants for new IDs
        // 2. Inner map key uses `id` instead of `e.getName()`, so entries land under the wrong key even when the outer map exists
        // Util1 fix needed: remove the containsKey guard (use computeIfAbsent) and change putIfAbsent(id, e) -> put(e.getName(), e)
        dataContainer.cxidAppPermissions.permissionSet
                .computeIfAbsent(cxid, k -> new java.util.HashMap<>())
                .put(permission, entry);
        saveDataContainer();
        log.info("[CXApp] Granted permission '{}' (weight {}) to {}", permission, weight, cxid);
    }

    /**
     * Revoke a CXID's permission for a CXApp operation.
     * Flips allow to false -- entry is retained for weight bookkeeping.
     * Full entry cleanup will be available in a future Util release.
     * Persisted immediately to DataContainer.
     */
    public void revokeCXIDPermission(String cxid, String permission, int weight) throws Exception {
        us.anvildevelopment.util.tools.permissions.BasicEntry denyEntry =
                new us.anvildevelopment.util.tools.permissions.BasicEntry(permission, false, weight);
        dataContainer.cxidAppPermissions.editPermission("SYSTEM", cxid, denyEntry);
        saveDataContainer();
        log.info("[CXApp] Revoked permission '{}' from {}", permission, cxid);
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
    public boolean validateBlockChronologically(CXNetwork network, Long chainID, NetworkBlock block,
                                               java.util.List<NetworkBlock> previousBlocks) {
        if (network == null || block == null) {
            return false;
        }

        // Build permission state by replaying all previous blocks
        // Start with genesis/initial permissions
        java.util.Map<String, java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry>> permissionState =
            new java.util.HashMap<>(network.networkPermissions.permissionSet);

        // Replay previous blocks to build current permission state
        if (previousBlocks != null) {
            for (NetworkBlock prevBlock : previousBlocks) {
                // Prepare previous block: verify and deserialize all events
                prevBlock.prepare(this);

                // Process permission-modifying events in this block
                for (NetworkEvent event : prevBlock.deserializedEvents.values()) {
                    updatePermissionState(permissionState, event, network);
                }
            }
        }

        // Prepare target block: verify and deserialize all events
        block.prepare(this);

        // Now validate each event in the target block against permission state
        for (java.util.Map.Entry<Integer, NetworkEvent> entry : block.deserializedEvents.entrySet()) {
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

    /**
     * Record an already-signed network event to the blockchain
     * Used for events received from other peers (already signed by sender)
     *
     * @param ne Deserialized network event
     * @param senderID Sender's cxID (must match signature)
     * @param signedBlob Pre-signed event bytes (cryptographically signed by sender)
     * @return true if recorded successfully
     */
    public boolean Event(NetworkEvent ne, String senderID, byte[] signedBlob) {
        if (ne == null || ne.p == null || signedBlob == null) {
            log.debug("[Blockchain] Event() null check failed: ne={}, ne.p={}, signedBlob={}", ne != null, ne != null && ne.p != null, signedBlob != null);
            return false;
        }

        // Get network ID from CXPath
        String networkID = ne.p.network;
        if (networkID == null || networkID.isEmpty()) {
            log.debug("[Blockchain] Event() networkID check failed: networkID={}", networkID);
            return false;
        }

        // Get the network
        CXNetwork network = networkMap.get(networkID);
        if (network == null) {
            log.debug("[Blockchain] Event() network not found: networkID={}", networkID);
            return false;
        }

        // Get chain ID from CXPath (now stored in path instead of inferred from event type)
        Long chainID = ne.p.chainID;
        if (chainID == null) {
            log.debug("[Blockchain] Event missing chain ID in path");
            return false;
        }

        // Get target chain
        NetworkRecord targetChain = null;
        if (chainID.equals(network.networkDictionary.c1)) {
            targetChain = network.c1;
        } else if (chainID.equals(network.networkDictionary.c2)) {
            targetChain = network.c2;
        } else if (chainID.equals(network.networkDictionary.c3)) {
            targetChain = network.c3;
        }

        if (targetChain == null) {
            log.debug("[Blockchain] Invalid chain ID: {} (c1={}, c2={}, c3={})", chainID, network.networkDictionary.c1, network.networkDictionary.c2, network.networkDictionary.c3);
            return false;
        }

        // Check if sender has permission to record to this chain
        if (!network.checkChainPermission(senderID, Permission.Record.name(), chainID)) {
            log.debug("[Blockchain] Event() permission denied: senderID={}, chainID={}", senderID != null ? senderID.substring(0, 8) : "null", chainID);
            return false;
        }

        // Synchronize on the chain to prevent concurrent modification
        synchronized (targetChain) {
            // Get current block
            NetworkBlock currentBlock = targetChain.current;
            if (currentBlock == null) {
                // Create genesis block if none exists
                currentBlock = new NetworkBlock(0L, chainID);
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
                    log.error("[Blockchain] Failed to persist block {} for chain {}: {}", currentBlock.block, chainID, e.getMessage());
                }

                // Create new block
                Long newBlockID = currentBlock.block + 1;
                NetworkBlock newBlock = new NetworkBlock(newBlockID, chainID);

                // Add to block map
                targetChain.blockMap.put(newBlockID, newBlock);

                // Update current block reference
                targetChain.current = newBlock;
                currentBlock = newBlock;
            }

            // Add event to current block with signed blob
            int eventIndex = currentBlock.networkEvents.size();
            if (!currentBlock.addEvent(eventIndex, signedBlob, this)) {
                log.error("[Blockchain] Failed to add event to block");
                return false;
            }
        }

        return true;
    }

    /**
     * @deprecated Recording should happen at transmission time using Event(ne, senderID, signedBlob)
     *             with the same blob being sent to peers. Do not use this method.
     */
    @Deprecated
    public boolean Event(NetworkEvent ne, String senderID) {
        log.error("[Blockchain] DEPRECATED: Event(ne, senderID) called - use Event(ne, senderID, signedBlob) at transmission time");
        return false;
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

    public CXNetwork getNetwork(String networkID) {
        if (!networkMap.containsKey(networkID)) return null;
        return networkMap.get(networkID);
    }

    public java.util.Map<String, CXNetwork> getNetworks() {
        return java.util.Collections.unmodifiableMap(networkMap);
    }

    public java.util.Set<String> getNetworkIDs() {
        return java.util.Collections.unmodifiableSet(networkMap.keySet());
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
                    nodeMesh.peerDirectory.lan.put(n.cxID, n);
                } catch (Exception e) {
                    log.error("Error in loadlan ", e);
                }
            }
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
     * Replay blockchain events to rebuild node state after restart
     *
     * Loads all blocks from disk and processes state-modifying events (executeOnSync=true)
     * to rebuild:
     * - registeredNodes (from REGISTER_NODE events in c1)
     * - blockedNodes (from BLOCK_NODE/UNBLOCK_NODE events in c1)
     * - Any other state tracked in blockchain
     *
     * Processing order: c1 (Admin) → c2 (Resources) → c3 (Events)
     * This ensures permissions and registrations are restored before other state
     *
     * @param network Network to replay blockchain for
     */
    public void replayBlockchain(CXNetwork network) {
        if (network == null) {
            log.error("[Blockchain Replay] Network is null, skipping");
            return;
        }

        String networkID = network.configuration.netID;
        log.info("[Blockchain Replay] Starting replay for network: {}", networkID);

        try {
            // Check if blockchain exists on disk
            File blockchainDir = new File(cxRoot, "blockchain/" + networkID);
            if (!blockchainDir.exists()) {
                log.info("[Blockchain Replay] No blockchain data on disk for {}", networkID);
                return;
            }

            int totalEventsReplayed = 0;
            int totalEventsSkipped = 0;

            // Replay c1 (Admin) chain first - contains registrations, blocks, permissions
            totalEventsReplayed += replayChain(network, network.networkDictionary.c1, "c1 (Admin)");

            // Replay c2 (Resources) chain - contains resource metadata
            totalEventsReplayed += replayChain(network, network.networkDictionary.c2, "c2 (Resources)");

            // Replay c3 (Events) chain - mostly ephemeral, but may have state events
            totalEventsReplayed += replayChain(network, network.networkDictionary.c3, "c3 (Events)");

            log.info("[Blockchain Replay] Complete for {}", networkID);
            log.info("[Blockchain Replay]   Events replayed: {}", totalEventsReplayed);
            log.info("[Blockchain Replay]   Events skipped (ephemeral): {}", totalEventsSkipped);

        } catch (Exception e) {
            log.error("[Blockchain Replay] Error replaying blockchain for {}: {}", networkID, e.getMessage());
        }
    }

    /**
     * Replay events from a specific chain
     * @return Number of events replayed
     */
    private int replayChain(CXNetwork network, Long chainID, String chainName) {
        int eventsReplayed = 0;
        String networkID = network.configuration.netID;

        try {
            log.info("[Blockchain Replay] Loading {}...", chainName);

            // Load chain from disk (load all blocks)
            NetworkRecord chain = blockchainPersistence.loadChain(networkID, chainID, true);

            if (chain == null || chain.blockMap.isEmpty()) {
                log.info("[Blockchain Replay]   No blocks found in {}", chainName);
                return 0;
            }

            log.info("[Blockchain Replay]   Found {} blocks", chain.blockMap.size());

            // Process blocks in order (0, 1, 2, ...)
            for (long blockNum = 0; blockNum < chain.blockMap.size(); blockNum++) {
                NetworkBlock block = chain.blockMap.get(blockNum);
                if (block == null) continue;

                // Prepare block: verify and deserialize all events
                block.prepare(this);

                // Process events in this block
                for (java.util.Map.Entry<Integer, NetworkEvent> entry : block.deserializedEvents.entrySet()) {
                    NetworkEvent event = entry.getValue();

                    // Only replay state-modifying events
                    if (event.executeOnSync) {
                        try {
                            // Queue event for processing through existing framework
                            // Create a simple NetworkContainer for the replayed event
                            NetworkContainer nc =
                                new NetworkContainer();
                            nc.se = "cxJSON1";
                            nc.s = false;

                            // Create InputBundle and add to eventQueue
                            InputBundle ib = new InputBundle(event, nc);
                            eventQueue.add(ib);

                            eventsReplayed++;

                            if (eventsReplayed <= 5 || eventsReplayed % 10 == 0) {
                                log.info("[Blockchain Replay]     Queued {} event (ID: {})", event.eT, event.iD);
                            }
                        } catch (Exception e) {
                            log.error("[Blockchain Replay]     Failed to queue event {}: {}", event.iD, e.getMessage());
                        }
                    }
                }
            }

            log.info("[Blockchain Replay]   {} complete: {} events replayed", chainName, eventsReplayed);

        } catch (Exception e) {
            log.error("[Blockchain Replay] Error loading {}: {}", chainName, e.getMessage());
        }

        return eventsReplayed;
    }

    /**
     * Automatically request chain status from NMI to trigger blockchain sync
     * Called after loading a network to ensure fresh peers sync blockchain data
     */
    private void requestChainStatusFromNMI(CXNetwork network) {
        if (network == null || network.configuration == null) {
            return;
        }

        String networkID = network.configuration.netID;

        try {
            // Check if network has NMI/Backend configured
            if (network.configuration.backendSet == null || network.configuration.backendSet.isEmpty()) {
                log.info("[Auto-Sync] No NMI configured for network {}, skipping auto-sync", networkID);
                return;
            }

            // Get first NMI from backend set
            String nmiID = network.configuration.backendSet.iterator().next();
            Node nmiNode = nodeMesh.peerDirectory.lookup(nmiID, true, true);

            if (nmiNode == null) {
                log.info("[Auto-Sync] NMI {} not found in peer directory, cannot request sync", nmiID);
                return;
            }

            log.info("[Auto-Sync] Requesting chain status from NMI {}...", nmiID.substring(0, 8));

            // Create CHAIN_STATUS_REQUEST
            String requestJson = serialize("cxJSON1", new ChainStatus(networkID));

            // Send request using EventBuilder pattern with automatic signature
            EventBuilder eb = buildEvent(
                EventType.CHAIN_STATUS_REQUEST,
                requestJson.getBytes(StandardCharsets.UTF_8)
            ).toPeer(nmiID).signData();
            eb.getPath().network = networkID;
            eb.queue();

            log.info("[Auto-Sync] Chain status request queued for NMI");

        } catch (Exception e) {
            log.error("[Auto-Sync] Error requesting chain status: {}", e.getMessage());
        }
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

    /**
     * Load DataContainer from disk (local security data)
     * Creates new container if file doesn't exist
     */
    private void loadDataContainer() {
        File dataFile = new File(cxRoot, "data.cxd");
        if (!dataFile.exists()) {
            dataContainer = new DataContainer();
        } else {
            try (FileInputStream fis = new FileInputStream(dataFile)) {
                dataContainer = (DataContainer) deserialize(
                    "cxJSON1", fis, DataContainer.class);
            } catch (Exception e) {
                log.error("[DataContainer] Failed to load data.cxd: {}", e.getMessage());
                createRecoveryMarker(cxRoot,
                    "data.cxd could not be read and may be corrupt.\n" +
                    "Saves for this node's local state are BLOCKED until this file is removed.\n" +
                    "Option A - Manual fix: Shut down the node, fix or replace data.cxd,\n" +
                    "  delete this file, then restart.\n" +
                    "Option B - Self-recovery: Delete this file while the node is running and ConnectX will attempt rebuild from peers.\n");
                dataContainer = new DataContainer();
            }
        }
        //This line is for testing / debug it should NOT be uncommented for production
        //dataContainer.cxikStore.putIfAbsent("", "chat-network");
    }

    /**
     * Save DataContainer to disk (persists whitelist/blocklist data)
     */
    public void saveDataContainer() throws Exception {
        if (dataContainer == null) {
            throw new IllegalStateException("DataContainer not initialized");
        }
        if (recoveryMarkerExists(cxRoot)) {
            log.warn("[DataContainer] Save blocked recovery.txt present in {}. Delete it to resume.", cxRoot.getName());
            return;
        }

        File dataFile = new File(cxRoot, "data.cxd");
        File tmpFile = new File(cxRoot, "data.cxd.tmp");
        String json = serialize("cxJSON1", dataContainer);
        try (FileWriter writer = new FileWriter(tmpFile)) {
            writer.write(json);
        }
        java.nio.file.Files.move(tmpFile.toPath(), dataFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    static void createRecoveryMarker(File dir, String message) {
        try {
            dir.mkdirs();
            File marker = new File(dir, "recovery.txt");
            if (!marker.exists()) {
                try (FileWriter fw = new FileWriter(marker)) {
                    fw.write(message);
                }
                LoggerFactory.getLogger(ConnectX.class).warn("[Recovery] Created recovery.txt in {}", dir.getName());
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(ConnectX.class).error("[Recovery] Could not create recovery.txt in {}: {}", dir.getName(), e.getMessage());
        }
    }

    static boolean recoveryMarkerExists(File dir) {
        return new File(dir, "recovery.txt").exists();
    }

    private void warnExistingRecoveryMarkers() {
        File marker = new File(cxRoot, "recovery.txt");
        if (marker.exists()) {
            log.warn("[Recovery] RECOVERY MODE ACTIVE: {} delete recovery.txt to resume normal operation", cxRoot.getName());
        }
        File blockchainDir = new File(cxRoot, "blockchain");
        if (blockchainDir.isDirectory()) {
            File[] networks = blockchainDir.listFiles(File::isDirectory);
            if (networks != null) {
                for (File net : networks) {
                    if (new File(net, "recovery.txt").exists()) {
                        log.warn("[Recovery] RECOVERY MODE ACTIVE for network {}: delete blockchain/{}/recovery.txt to resume.", net.getName(), net.getName());
                    }
                }
            }
        }
    }

    public void atomicWriteBytes(File target, byte[] data) throws Exception {
        File tmp = new File(target.getParent(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
        }
        java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    void atomicFinalize(File tmp, File target) {
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.error("[IO] Failed to finalize atomic write for {}: {}", target.getName(), e.getMessage());
        }
    }

    /**
     * Returns true if the address string is well-formed and safe to route to.
     *
     * Bridge addresses ("protocol:url") are validated by the registered BridgeProvider
     * for that protocol — each bridge owns its own spec.
     * Plain IP:port addresses are validated inline (valid port 1-65535, non-empty host).
     * Null, empty, or addresses that match no registered bridge and are not valid IP:port
     * are rejected.
     */
    public boolean isValidPeerAddress(String addr) {
        if (addr == null || addr.isEmpty() || addr.length() > 256) return false;

        // Bridge address: contains "://" after the protocol prefix
        if (addr.contains("://")) {
            int firstColon = addr.indexOf(':');
            if (firstColon <= 0) return false;
            String protocol = addr.substring(0, firstColon);
            BridgeProvider provider = bridgeProviders.get(protocol);
            if (provider == null) return false; // Unknown bridge protocol
            return provider.isValidAddress(addr);
        }

        // Plain IP:port
        int lastColon = addr.lastIndexOf(':');
        if (lastColon <= 0) return false;
        String host = addr.substring(0, lastColon);
        if (host.isEmpty()) return false;
        try {
            int port = Integer.parseInt(addr.substring(lastColon + 1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Returns true if the given address string refers to this node's own endpoint.
     * Handles three formats:
     *   - Socket toString format:  /127.0.0.1:9001  (from ownAddresses)
     *   - Plain IP:port:           127.0.0.1:9001   (from waitingAddresses / PeerFinding)
     *   - Bridge address:          cxHTTP1:https://.../cx
     *
     * Checks in order:
     *   1. Direct match against self.addr
     *   2. Normalized match against all entries in nodeMesh.ownAddresses
     *   3. Port-based match: port == listeningPort and IP is own local or loopback
     */
    public boolean isSelfAddress(String addr) {
        if (addr == null || addr.isEmpty()) return false;

        // Normalize: strip leading slash from socket-toString format
        String normalized = addr.startsWith("/") ? addr.substring(1) : addr;

        // 1. Direct match against advertised self address
        if (self != null && self.addr != null && normalized.equals(self.addr)) return true;

        // 2. Normalized match against all accumulated own addresses
        if (nodeMesh != null) {
            for (String own : nodeMesh.ownAddresses) {
                if (own == null) continue;
                String ownNorm = own.startsWith("/") ? own.substring(1) : own;
                if (normalized.equals(ownNorm)) return true;
            }
        }

        // 3. Port-based check for plain IP:port addresses (no protocol scheme)
        if (listeningPort > 0 && normalized.contains(":") && !normalized.contains("://")) {
            try {
                int lastColon = normalized.lastIndexOf(':');
                int port = Integer.parseInt(normalized.substring(lastColon + 1));
                if (port == listeningPort) {
                    String ip = normalized.substring(0, lastColon);
                    String localIP = LANScanner.getLocalIP();
                    if ("127.0.0.1".equals(ip) || "localhost".equals(ip)
                            || "0.0.0.0".equals(ip)
                            || (localIP != null && localIP.equals(ip))) {
                        return true;
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        return false;
    }

    public void updateHTTPBridgePort(Integer port) throws Exception {
        if (bridgeProviders.containsKey("cxHTTP1")) {
            BridgeProvider bridge = bridgeProviders.get("cxHTTP1");
            bridge.stopServer();
            bridge.startServer(port);
        }
    }

}
