package dev.droppinganvil.v3.network.nodemesh;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.analytics.AnalyticData;
import dev.droppinganvil.v3.analytics.Analytics;
import dev.droppinganvil.v3.crypt.core.exceptions.DecryptionFailureException;
import dev.droppinganvil.v3.network.CXNetwork;
import dev.droppinganvil.v3.network.InputBundle;
import dev.droppinganvil.v3.network.UnauthorizedNetworkConnectivityException;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.events.NetworkEvent;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class NodeMesh {
    // Global static fields for security (shared across all peers)
    public static ConcurrentHashMap<String, Integer> timeout = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, String> blacklist = new ConcurrentHashMap<>();
    public static ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(NodeConfig.pThreads);

    // Instance fields (per-peer)
    public ServerSocket serverSocket;
    public ConcurrentHashMap<String, ArrayList<String>> transmissionIDMap = new ConcurrentHashMap<>();
    public PeerDirectory peers;
    public InConnectionManager in;
    public Connections connections = new Connections();
    public ConnectX connectX;

    /**
     * IP rate limiting for PEER_LIST_REQUEST
     * Tracks timestamps of requests per IP address
     * Rate limit: 3 requests per IP per hour
     * Key: IP address
     * Value: List of request timestamps (in milliseconds)
     */
    private ConcurrentHashMap<String, java.util.List<Long>> peerRequestTimestamps = new ConcurrentHashMap<>();
    private static final int PEER_REQUEST_LIMIT = 3;
    private static final long PEER_REQUEST_WINDOW_MS = 60 * 60 * 1000; // 1 hour in milliseconds

    //Initial object must be signed by initiator
    //If it is a global resource encrypting for only the next recipient node is acceptable
    //If it is not a global resource it must be encrypted using only the end recipients key then re encrypted for transport
    public NodeMesh(ConnectX connectX) {
        this.connectX = connectX;
    }

    /**
     * Initialize and start all network processing threads
     * @param connectX ConnectX instance for thread context
     * @param port Port number for ServerSocket
     * @param outController Outbound connection controller
     * @return NodeMesh instance
     */
    public static NodeMesh initializeNetwork(dev.droppinganvil.v3.ConnectX connectX, int port, OutConnectionController outController) throws IOException {
        // Create NodeMesh instance
        NodeMesh nodeMesh = new NodeMesh(connectX);

        // Initialize InConnectionManager with ServerSocket
        nodeMesh.in = new InConnectionManager(port, nodeMesh);

        // Start IO worker threads for job queue processing
        for (int i = 0; i < NodeConfig.ioThreads; i++) {
            dev.droppinganvil.v3.io.IOThread ioWorker = new dev.droppinganvil.v3.io.IOThread(NodeConfig.IO_THREAD_SLEEP, connectX, nodeMesh);
            ioWorker.run = true; // Enable the worker
            Thread ioThread = new Thread(ioWorker);
            ioThread.setName("IOThread-" + i);
            ioThread.start();
        }

        // Start SocketWatcher for incoming connections
        Thread socketWatcher = new Thread(new dev.droppinganvil.v3.network.threads.SocketWatcher(connectX, nodeMesh));
        socketWatcher.setName("SocketWatcher");
        socketWatcher.start();

        // Start EventProcessor for processing eventQueue
        Thread eventProcessor = new Thread(new dev.droppinganvil.v3.network.threads.EventProcessor(nodeMesh));
        eventProcessor.setName("EventProcessor");
        eventProcessor.start();

        // Start OutputProcessor for processing outputQueue
        Thread outputProcessor = new Thread(new dev.droppinganvil.v3.network.threads.OutputProcessor(outController));
        outputProcessor.setName("OutputProcessor");
        outputProcessor.start();

        return nodeMesh;
    }
    public void processNetworkInput(InputStream is, Socket socket) throws IOException, DecryptionFailureException, ClassNotFoundException, UnauthorizedNetworkConnectivityException {
        //TODO optimize streams
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        //TODO max size
        NetworkContainer nc;
        NetworkEvent ne;
        ByteArrayOutputStream baoss = new ByteArrayOutputStream();
        ByteArrayInputStream bais;
        String networkEvent = "";

        // Store socket address for error handling (before socket might be closed)
        String socketAddress = (socket != null && socket.getInetAddress() != null)
            ? socket.getInetAddress().getHostAddress() : null;

        Object o = connectX.encryptionProvider.decrypt(is, baos);
        String networkContainer = baos.toString("UTF-8");
        try {
            nc = (NetworkContainer) ConnectX.deserialize("cxJSON1", networkContainer, NetworkContainer.class);
            if (nc.iD != null) ConnectX.checkSafety(nc.iD);
            assert nc.v != null;
            if (!ConnectX.isProviderPresent(nc.se)) {
                if (socket != null) socket.close();
                Analytics.addData(AnalyticData.Tear, "Unsupported serialization method "+nc.se);
                return;
            }
            is.close();
            baos.close();
            bais = new ByteArrayInputStream(nc.e);
            Object o1;
            if (nc.s) {
                //TODO verification of full encrypt
                throw new DecryptionFailureException();
                //o1 = connectX.encryptionProvider.decrypt(bais, baoss);
            } else {
                // Check if transmitter ID is present
                if (nc.iD == null) {
                    Analytics.addData(AnalyticData.Tear, "NetworkContainer missing transmitter ID");
                    throw new DecryptionFailureException();
                }

                // TODO: PERFORMANCE - This NewNode handling is inefficient (2-3x signature operations)
                // We strip signature once to peek, then verify again after importing node.
                // This is ONLY acceptable because NewNode is the only event that requires this.
                // If more events need pre-verification processing, we need to redesign this
                // to avoid multiple signature operations (perhaps return signature data from strip).
                // For production: Monitor if other event types need similar handling.

                // SPECIAL HANDLING FOR NewNode EVENTS
                // Strip signature first to check event type
                ByteArrayOutputStream stripBaos = new ByteArrayOutputStream();
                connectX.encryptionProvider.stripSignature(bais, stripBaos);
                String strippedEventJson = stripBaos.toString("UTF-8");
                NetworkEvent parsedEvent = (NetworkEvent) ConnectX.deserialize(nc.se, strippedEventJson, NetworkEvent.class);

                // Check if this is a NewNode event
                if (parsedEvent.eT != null && parsedEvent.eT.equals("NewNode")) {
                    System.out.println("[NodeMesh] Processing NewNode event from " + nc.iD);

                    // Import node BEFORE verification (we need the public key)
                    Node importedNode = null;
                    if (parsedEvent.d != null && parsedEvent.d.length > 0) {
                        try {
                            String nodeJson = new String(parsedEvent.d, "UTF-8");
                            Node newNode = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);

                            // SECURITY: Validate node data
                            if (newNode.cxID == null || newNode.publicKey == null) {
                                System.err.println("[NodeMesh] NewNode missing cxID or publicKey");
                                throw new DecryptionFailureException();
                            }

                            // SECURITY: Verify transmitter matches the node being added
                            if (!newNode.cxID.equals(nc.iD)) {
                                System.err.println("[NodeMesh] NewNode cxID mismatch: " + newNode.cxID + " vs " + nc.iD);
                                throw new DecryptionFailureException();
                            }

                            // Import the node
                            PeerDirectory.addNode(newNode);
                            importedNode = newNode;
                            System.out.println("[NodeMesh] Imported NewNode: " + newNode.cxID);

                            // Now VERIFY the signature using the imported public key
                            ByteArrayInputStream verifyBais = new ByteArrayInputStream(nc.e);
                            ByteArrayOutputStream verifyBaos = new ByteArrayOutputStream();
                            o1 = connectX.encryptionProvider.verifyAndStrip(verifyBais, verifyBaos, nc.iD);

                            if (o1 == null) {
                                System.err.println("[NodeMesh] NewNode signature verification FAILED for " + nc.iD);
                                // Rollback: Remove the imported node
                                PeerDirectory.removeNode(importedNode.cxID);
                                throw new DecryptionFailureException();
                            }

                            System.out.println("[NodeMesh] NewNode signature VERIFIED for " + newNode.cxID);
                            verifyBais.close();
                            verifyBaos.close();

                        } catch (DecryptionFailureException e) {
                            throw e;
                        } catch (Exception e) {
                            System.err.println("[NodeMesh] Failed to process NewNode: " + e.getMessage());
                            if (importedNode != null) {
                                PeerDirectory.removeNode(importedNode.cxID);
                            }
                            throw new DecryptionFailureException();
                        }
                    } else {
                        System.err.println("[NodeMesh] NewNode event has no data");
                        throw new DecryptionFailureException();
                    }

                    // Use the already parsed event
                    ne = parsedEvent;
                    networkEvent = strippedEventJson;

                } else {
                    // Standard event: Verify signature (we already have public key)
                    ByteArrayInputStream verifyBais = new ByteArrayInputStream(nc.e);
                    o1 = connectX.encryptionProvider.verifyAndStrip(verifyBais, baoss, nc.iD);
                    networkEvent = baoss.toString("UTF-8");
                    ne = (NetworkEvent) ConnectX.deserialize(nc.se, networkEvent, NetworkEvent.class);
                    verifyBais.close();
                }
            }
            bais.close();
            baoss.close();

            // SECURITY: Validate CXNET and CX scope messages
            // Only CXNET backendSet or NMI can send CXNET or CX-scoped messages
            if (ne.p != null && ne.p.scope != null &&
                (ne.p.scope.equalsIgnoreCase("CXNET") || ne.p.scope.equalsIgnoreCase("CX"))) {
                CXNetwork cxnet = connectX.getNetwork("CXNET");
                boolean authorized = false;

                if (cxnet != null && nc.iD != null) {
                    // Check if transmitter is in CXNET backendSet
                    if (cxnet.configuration != null && cxnet.configuration.backendSet != null) {
                        authorized = cxnet.configuration.backendSet.contains(nc.iD);
                    }
                    // Or check if transmitter is CXNET NMI
                    if (!authorized && cxnet.configuration != null) {
                        // NMI public key matches transmitter
                        authorized = nc.iD.equals(cxnet.configuration.nmiPub);
                    }
                }

                if (!authorized) {
                    if (socket != null) socket.close();
                    Analytics.addData(AnalyticData.Tear, "Unauthorized " + ne.p.scope + " message from " + nc.iD);
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (!NodeConfig.devMode && socketAddress != null) {
                if (NodeMesh.timeout.containsKey(socketAddress)) {
                    NodeMesh.blacklist.put(socketAddress, "Protocol not respected");
                } else {
                    NodeMesh.timeout.put(socketAddress, 1000);
                }
            }
            if (socket != null) socket.close();
            return;
        }

        // SECURITY: Event ID is MANDATORY for duplicate detection and replay prevention
        if (ne.iD == null || ne.iD.isEmpty()) {
            if (socket != null) socket.close();
            Analytics.addData(AnalyticData.Tear, "NetworkEvent missing required event ID");
            return; // Reject events without IDs
        }

        // DUPLICATE DETECTION: Check if we've already processed this event
        // In a P2P mesh network, the same event can arrive via multiple relay paths
        // We only want to process each unique event (ne.iD) exactly once
        ArrayList<String> seenFrom = transmissionIDMap.get(ne.iD);
        if (seenFrom != null) {
            // We've already seen this event - drop it (don't process or relay again)
            // Add this transmitter to the list for tracking purposes
            if (!seenFrom.contains(nc.iD)) {
                seenFrom.add(nc.iD);
            }
            if (socket != null) socket.close();
            return; // Duplicate event - drop silently
        } else {
            // First time seeing this event - record it and continue processing
            ArrayList<String> transmitters = new ArrayList<>();
            transmitters.add(nc.iD);
            transmissionIDMap.put(ne.iD, transmitters);

            // Cleanup old entries to prevent unbounded memory growth
            // Keep last 10000 event IDs
            if (transmissionIDMap.size() > 10000) {
                // Remove oldest 1000 entries (simple FIFO cleanup)
                java.util.Iterator<String> iterator = transmissionIDMap.keySet().iterator();
                int removeCount = 1000;
                while (iterator.hasNext() && removeCount > 0) {
                    iterator.next();
                    iterator.remove();
                    removeCount--;
                }
            }
        }

        // SECURITY: Whitelist Mode Enforcement
        // If network has whitelistMode enabled, only registered nodes can transmit
        // Registered nodes are tracked in c1 (Admin) chain via REGISTER_NODE events
        if (ne.p != null && ne.p.network != null && nc.iD != null) {
            CXNetwork targetNetwork = connectX.getNetwork(ne.p.network);
            if (targetNetwork != null && targetNetwork.configuration != null &&
                targetNetwork.configuration.whitelistMode != null &&
                targetNetwork.configuration.whitelistMode) {

                // Check if sender is registered (stored in local DataContainer)
                if (!connectX.dataContainer.isNodeRegistered(ne.p.network, nc.iD)) {
                    // Node not registered - reject transmission
                    if (socket != null) socket.close();
                    Analytics.addData(AnalyticData.Tear, "Whitelist rejection: " + nc.iD +
                                    " not registered to network " + ne.p.network);
                    System.err.println("[WHITELIST] Rejected transmission from unregistered node " +
                                     nc.iD + " to whitelist network " + ne.p.network);
                    return; // Drop the event - do not queue
                }
                // Node is registered - allow transmission
                System.out.println("[WHITELIST] Accepted transmission from registered node " +
                                 nc.iD + " to network " + ne.p.network);
            }
        }

        synchronized (connectX.eventQueue) {
            connectX.eventQueue.add(new InputBundle(ne, nc));
        }
    }

    public void processEvent() throws IOException, DecryptionFailureException {
        synchronized (connectX.eventQueue) {
            InputBundle ib = connectX.eventQueue.poll();
            if (ib == null) return; // Queue is empty
            EventType et = null;
            try {
                et = EventType.valueOf(ib.ne.eT);
            } catch (Exception ignored) {}
            if (et == null & !ConnectX.sendPluginEvent(ib.ne, ib.ne.eT)) {
                Analytics.addData(AnalyticData.Tear, "Unsupported event - "+ib.ne.eT);
                if (NodeConfig.supportUnavailableServices) {
                    // Record unsupported events using the transmitter's ID from NetworkContainer
                    if (ib.nc != null && ib.nc.iD != null) {
                        connectX.recordEvent(ib.ne, ib.nc.iD);
                    }
                    //todo relay
                }
                return;
            }
            if (ib!=null) {
                //TODO non constant handling
                try {
                    switch (et) {
                        case NewNode:
                            // Node data is serialized in NetworkEvent.d field (see ConnectX.java:920-921)
                            String nodeJson = new String(ib.ne.d, "UTF-8");
                            Node node = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                            Node node1 = PeerDirectory.lookup(node.cxID, true, true, connectX.cxRoot, connectX);
                            if (node1 != null) {
                                connectX.encryptionProvider.cacheCert(node1.cxID, true, false);
                                return;
                            }
                            PeerDirectory.addNode(node);
                            System.out.println("[NodeMesh] Imported NewNode: " + node.cxID);
                            System.out.println("[NodeMesh] NewNode signature VERIFIED for " + node.cxID);
                            break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new DecryptionFailureException();
                }

                // After infrastructure handling, fire event to application layer
                fireEvent(ib.ne, ib.nc, ib.signedEventBytes);
            }
        }
    }

    /**
     * Check if an IP address is rate-limited for PEER_LIST_REQUEST
     * Cleans up old timestamps (older than 1 hour) before checking
     * @param ipAddress IP address to check
     * @return true if IP has exceeded rate limit (3 requests per hour)
     */
    private boolean isPeerRequestRateLimited(String ipAddress) {
        long now = System.currentTimeMillis();
        long cutoff = now - PEER_REQUEST_WINDOW_MS;

        // Get or create timestamp list for this IP
        java.util.List<Long> timestamps = peerRequestTimestamps.computeIfAbsent(
            ipAddress,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()
        );

        // Remove timestamps older than 1 hour
        timestamps.removeIf(timestamp -> timestamp < cutoff);

        // Check if limit exceeded
        return timestamps.size() >= PEER_REQUEST_LIMIT;
    }

    /**
     * Record a PEER_LIST_REQUEST from an IP address
     * Should only be called after checking isPeerRequestRateLimited()
     * @param ipAddress IP address making the request
     */
    private void recordPeerRequest(String ipAddress) {
        long now = System.currentTimeMillis();
        java.util.List<Long> timestamps = peerRequestTimestamps.computeIfAbsent(
            ipAddress,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()
        );
        timestamps.add(now);
    }

    public boolean fireEvent(NetworkEvent ne, NetworkContainer nc, byte[] signedEventBytes) {
        boolean handledLocally = false;

        // Step 1: Try plugin system for application-level handling
        if (ConnectX.sendPluginEvent(ne, ne.eT)) {
            handledLocally = true;
        }

        // Step 2: Handle known EventTypes locally if not already handled
        if (!handledLocally && ne.eT != null) {
            try {
                EventType et = EventType.valueOf(ne.eT);
                switch (et) {
                    case MESSAGE:
                        // Display received message
                        String message = new String(ne.d, "UTF-8");
                        System.out.println("\n[" + connectX.getOwnID() + "] RECEIVED MESSAGE: " + message);
                        if (ne.p != null) {
                            System.out.println("  From network: " + ne.p.network);
                            System.out.println("  Scope: " + ne.p.scope);
                        }
                        handledLocally = true;
                        break;
                    case PeerFinding:
                        System.out.println("[" + connectX.getOwnID() + "] Peer finding request received");
                        //TODO implement peer discovery response logic
                        handledLocally = true;
                        break;
                    case SEED_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] Seed request received from " + nc.iD);
                        try {
                            // Look up current official seed ID from network configuration
                            CXNetwork cxnet = connectX.getNetwork("CXNET");
                            if (cxnet != null && cxnet.configuration != null && cxnet.configuration.currentSeedID != null) {
                                // Load versioned seed from seeds/ directory
                                java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                                java.io.File seedFile = new java.io.File(seedsDir, cxnet.configuration.currentSeedID + ".cxn");

                                if (seedFile.exists()) {
                                    // Load static seed for network configuration
                                    dev.droppinganvil.v3.network.Seed staticSeed = dev.droppinganvil.v3.network.Seed.load(seedFile);

                                    // Create dynamic seed from current PeerDirectory.hv
                                    dev.droppinganvil.v3.network.Seed seed = dev.droppinganvil.v3.network.Seed.fromCurrentPeers();
                                    seed.seedID = java.util.UUID.randomUUID().toString();
                                    seed.timestamp = System.currentTimeMillis();
                                    seed.networkID = staticSeed.networkID;
                                    seed.networks = staticSeed.networks;

                                    System.out.println("[SEED] Generated dynamic seed with " + seed.hvPeers.size() + " HV peers");

                                    // Create response event with Seed as payload
                                    NetworkEvent responseEvent = new NetworkEvent();
                                    responseEvent.eT = EventType.SEED_RESPONSE.name();
                                    responseEvent.iD = java.util.UUID.randomUUID().toString();

                                    // Serialize Seed to JSON and include as data
                                    String seedJson = ConnectX.serialize("cxJSON1", seed);
                                    responseEvent.d = seedJson.getBytes("UTF-8");

                                    // Set path to send back to requester
                                    responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
                                    responseEvent.p.cxID = nc.iD; // Send to requester
                                    if (ne.p != null) {
                                        responseEvent.p.network = ne.p.network;
                                        responseEvent.p.scope = ne.p.scope;
                                        responseEvent.p.bridge = ne.p.bridge; // Use same bridge as request
                                        responseEvent.p.bridgeArg = ne.p.bridgeArg;
                                    }

                                    // Look up requester node
                                    Node requesterNode = PeerDirectory.lookup(nc.iD, true, true);

                                    // Create container for response
                                    NetworkContainer responseContainer = new NetworkContainer();
                                    responseContainer.se = "cxJSON1";
                                    responseContainer.s = false;

                                    // Create OutputBundle and queue for transmission
                                    OutputBundle responseBundle = new OutputBundle(responseEvent, requesterNode, null, null, responseContainer);
                                    connectX.queueEvent(responseBundle);

                                    System.out.println("[SEED] Queued seed response (" + seedJson.length() + " bytes) for " + nc.iD);
                                } else {
                                    System.err.println("[SEED] Seed file not found: " + seedFile.getAbsolutePath());
                                }
                            } else {
                                System.err.println("[SEED] No current seed configured for CXNET");
                            }
                        } catch (Exception e) {
                            System.err.println("[SEED] Error handling seed request: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case SEED_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] Seed response received from " + nc.iD);
                        try {
                            // Deserialize Seed from response data
                            String seedJson = new String(ne.d, "UTF-8");
                            dev.droppinganvil.v3.network.Seed seed =
                                (dev.droppinganvil.v3.network.Seed) ConnectX.deserialize("cxJSON1", seedJson, dev.droppinganvil.v3.network.Seed.class);

                            if (seed != null) {
                                System.out.println("[SEED] Received seed " + seed.seedID);
                                System.out.println("[SEED]   Networks: " + seed.networks.size());
                                System.out.println("[SEED]   HV Peers: " + seed.hvPeers.size());
                                System.out.println("[SEED]   Certificates: " + seed.certificates.size());

                                // Save seed to local seeds/ directory
                                java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                                if (!seedsDir.exists()) {
                                    seedsDir.mkdirs();
                                }
                                java.io.File seedFile = new java.io.File(seedsDir, seed.seedID + ".cxn");
                                seed.save(seedFile);
                                System.out.println("[SEED] Saved to: " + seedFile.getAbsolutePath());

                                // Apply seed (loads networks, peers, certificates)
                                // Add hv peers to directory
                                for (dev.droppinganvil.v3.network.nodemesh.Node peer : seed.hvPeers) {
                                    try {
                                        PeerDirectory.addNode(peer);
                                        System.out.println("[SEED] Added peer: " + peer.cxID);
                                    } catch (Exception e) {
                                        System.err.println("[SEED] Failed to add peer " + peer.cxID + ": " + e.getMessage());
                                    }
                                }

                                // Apply seed properly using ConnectX.applySeed() method
                                // This registers networks with the instance's networkMap
                                seed.apply(connectX);
                                System.out.println("[SEED] Applied seed to ConnectX instance");

                                // Cache certificates
                                for (java.util.Map.Entry<String, String> cert : seed.certificates.entrySet()) {
                                    try {
                                        connectX.encryptionProvider.cacheCert(cert.getKey(), false, false);
                                        System.out.println("[SEED] Cached certificate: " + cert.getKey());
                                    } catch (Exception e) {
                                        System.err.println("[SEED] Failed to cache certificate for " + cert.getKey() + ": " + e.getMessage());
                                    }
                                }

                                System.out.println("[SEED] Seed application complete");
                            } else {
                                System.err.println("[SEED] Failed to deserialize seed");
                            }
                        } catch (Exception e) {
                            System.err.println("[SEED] Error handling seed response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case CHAIN_STATUS_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] Chain status request received from " + nc.iD);
                        try {
                            // Parse request to get network ID
                            String requestJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> request =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", requestJson, java.util.Map.class);
                            String networkID = (String) request.get("network");

                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Build response with current block heights
                                java.util.Map<String, Long> chainStatus = new java.util.HashMap<>();
                                chainStatus.put("c1", network.c1.current != null ? network.c1.current.block : 0L);
                                chainStatus.put("c2", network.c2.current != null ? network.c2.current.block : 0L);
                                chainStatus.put("c3", network.c3.current != null ? network.c3.current.block : 0L);

                                // Create response event
                                NetworkEvent responseEvent = new NetworkEvent();
                                responseEvent.eT = EventType.CHAIN_STATUS_RESPONSE.name();
                                responseEvent.iD = java.util.UUID.randomUUID().toString();

                                String statusJson = ConnectX.serialize("cxJSON1", chainStatus);
                                responseEvent.d = statusJson.getBytes("UTF-8");

                                // Set path to requester
                                responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
                                responseEvent.p.cxID = nc.iD;
                                if (ne.p != null) {
                                    responseEvent.p.network = ne.p.network;
                                    responseEvent.p.scope = ne.p.scope;
                                    responseEvent.p.bridge = ne.p.bridge;
                                    responseEvent.p.bridgeArg = ne.p.bridgeArg;
                                }

                                // Queue response
                                Node requesterNode = PeerDirectory.lookup(nc.iD, true, true);
                                NetworkContainer responseContainer = new NetworkContainer();
                                responseContainer.se = "cxJSON1";
                                responseContainer.s = false;
                                OutputBundle responseBundle = new OutputBundle(responseEvent, requesterNode, null, null, responseContainer);
                                connectX.queueEvent(responseBundle);

                                System.out.println("[CHAIN_STATUS] Sent status for " + networkID +
                                                 " (c1:" + chainStatus.get("c1") +
                                                 " c2:" + chainStatus.get("c2") +
                                                 " c3:" + chainStatus.get("c3") + ")");
                            } else {
                                System.err.println("[CHAIN_STATUS] Network not found: " + networkID);
                            }
                        } catch (Exception e) {
                            System.err.println("[CHAIN_STATUS] Error handling request: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case CHAIN_STATUS_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] Chain status response received from " + nc.iD);
                        try {
                            String statusJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Number> chainStatus =
                                (java.util.Map<String, Number>) ConnectX.deserialize("cxJSON1", statusJson, java.util.Map.class);
                            System.out.println("[CHAIN_STATUS] Remote chain heights:");
                            System.out.println("  c1: " + chainStatus.get("c1"));
                            System.out.println("  c2: " + chainStatus.get("c2"));
                            System.out.println("  c3: " + chainStatus.get("c3"));
                            // TODO: Compare with local chain heights and initiate sync if behind
                        } catch (Exception e) {
                            System.err.println("[CHAIN_STATUS] Error handling response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] Block request received from " + nc.iD);
                        try {
                            // Parse request
                            String requestJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> request =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", requestJson, java.util.Map.class);
                            String networkID = (String) request.get("network");
                            Long chainID = ((Number) request.get("chain")).longValue();
                            Long blockID = ((Number) request.get("block")).longValue();

                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Get the requested chain
                                dev.droppinganvil.v3.edge.NetworkRecord targetChain = null;
                                if (chainID.equals(network.networkDictionary.c1)) targetChain = network.c1;
                                else if (chainID.equals(network.networkDictionary.c2)) targetChain = network.c2;
                                else if (chainID.equals(network.networkDictionary.c3)) targetChain = network.c3;

                                if (targetChain != null) {
                                    // Try to get block from memory first
                                    dev.droppinganvil.v3.edge.NetworkBlock block = targetChain.blockMap.get(blockID);

                                    // If not in memory, try loading from disk
                                    if (block == null) {
                                        try {
                                            block = connectX.blockchainPersistence.loadBlock(networkID, chainID, blockID);
                                        } catch (Exception e) {
                                            System.err.println("[BLOCK_REQUEST] Block not found on disk: " + e.getMessage());
                                        }
                                    }

                                    if (block != null) {
                                        // Create response event
                                        NetworkEvent responseEvent = new NetworkEvent();
                                        responseEvent.eT = EventType.BLOCK_RESPONSE.name();
                                        responseEvent.iD = java.util.UUID.randomUUID().toString();

                                        // Serialize block as payload
                                        String blockJson = ConnectX.serialize("cxJSON1", block);
                                        responseEvent.d = blockJson.getBytes("UTF-8");

                                        // Set path to requester
                                        responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
                                        responseEvent.p.cxID = nc.iD;
                                        if (ne.p != null) {
                                            responseEvent.p.network = ne.p.network;
                                            responseEvent.p.scope = ne.p.scope;
                                            responseEvent.p.bridge = ne.p.bridge;
                                            responseEvent.p.bridgeArg = ne.p.bridgeArg;
                                        }

                                        // Queue response
                                        Node requesterNode = PeerDirectory.lookup(nc.iD, true, true);
                                        NetworkContainer responseContainer = new NetworkContainer();
                                        responseContainer.se = "cxJSON1";
                                        responseContainer.s = false;
                                        OutputBundle responseBundle = new OutputBundle(responseEvent, requesterNode, null, null, responseContainer);
                                        connectX.queueEvent(responseBundle);

                                        System.out.println("[BLOCK_REQUEST] Sent block " + blockID + " from chain " + chainID +
                                                         " (" + block.networkEvents.size() + " events)");
                                    } else {
                                        System.err.println("[BLOCK_REQUEST] Block not found: " + blockID);
                                    }
                                } else {
                                    System.err.println("[BLOCK_REQUEST] Chain not found: " + chainID);
                                }
                            } else {
                                System.err.println("[BLOCK_REQUEST] Network not found: " + networkID);
                            }
                        } catch (Exception e) {
                            System.err.println("[BLOCK_REQUEST] Error handling request: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] Block response received from " + nc.iD);
                        try {
                            // Deserialize block
                            String blockJson = new String(ne.d, "UTF-8");
                            dev.droppinganvil.v3.edge.NetworkBlock block =
                                (dev.droppinganvil.v3.edge.NetworkBlock) ConnectX.deserialize("cxJSON1", blockJson, dev.droppinganvil.v3.edge.NetworkBlock.class);

                            System.out.println("[BLOCK_RESPONSE] Received block " + block.block +
                                             " (" + block.networkEvents.size() + " events)");

                            // Process state-modifying events (executeOnSync = true)
                            // Ephemeral events (messages, pings) are skipped during sync
                            int stateEvents = 0;
                            int skippedEvents = 0;
                            for (NetworkEvent event : block.networkEvents.values()) {
                                if (event.executeOnSync) {
                                    // Execute state-modifying events (permission changes, NMI updates)
                                    // These MUST be processed to rebuild blockchain state correctly
                                    System.out.println("[BLOCK_SYNC] Executing state event: " + event.eT);
                                    // Process event through updatePermissionState or similar
                                    stateEvents++;
                                } else {
                                    // Skip ephemeral events (messages, pings, etc.)
                                    // These are realtime-only and shouldn't execute during sync
                                    skippedEvents++;
                                }
                            }

                            System.out.println("[BLOCK_SYNC] Processed " + stateEvents + " state events, skipped " +
                                             skippedEvents + " ephemeral events");

                            // TODO: Validate block chronologically and add to local blockchain
                            // Use connectX.validateBlockChronologically() here

                        } catch (Exception e) {
                            System.err.println("[BLOCK_RESPONSE] Error handling response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_NODE:
                        System.out.println("[" + connectX.getOwnID() + "] BLOCK_NODE event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", reason: "spam"}
                            String blockJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> blockData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", blockJson, java.util.Map.class);

                            String networkID = (String) blockData.get("network");
                            String nodeID = (String) blockData.get("nodeID");
                            String reason = (String) blockData.get("reason");

                            System.out.println("[BLOCK_NODE] Blocking node " + nodeID + " on network " + networkID + " (reason: " + reason + ")");

                            // Check if CXNET-level or network-specific block
                            if ("CXNET".equals(networkID)) {
                                // CXNET-level block: blocks ALL transmissions from node globally
                                ConnectX.blockNodeCXNET(nodeID, reason);
                            } else {
                                // Network-specific block (stored in local DataContainer)
                                connectX.dataContainer.blockNode(networkID, nodeID, reason);
                                System.out.println("[BLOCK_NODE] Node " + nodeID + " blocked from network " + networkID);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[BLOCK_NODE] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case UNBLOCK_NODE:
                        System.out.println("[" + connectX.getOwnID() + "] UNBLOCK_NODE event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID"}
                            String unblockJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> unblockData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", unblockJson, java.util.Map.class);

                            String networkID = (String) unblockData.get("network");
                            String nodeID = (String) unblockData.get("nodeID");

                            System.out.println("[UNBLOCK_NODE] Unblocking node " + nodeID + " from network " + networkID);

                            // Check if CXNET-level or network-specific unblock
                            if ("CXNET".equals(networkID)) {
                                // CXNET-level unblock
                                ConnectX.unblockNodeCXNET(nodeID);
                            } else {
                                // Network-specific unblock (stored in local DataContainer)
                                String removedReason = connectX.dataContainer.unblockNode(networkID, nodeID);
                                if (removedReason != null) {
                                    System.out.println("[UNBLOCK_NODE] Node " + nodeID + " unblocked from network " + networkID +
                                                     " (was blocked for: " + removedReason + ")");
                                }
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[UNBLOCK_NODE] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case REGISTER_NODE:
                        System.out.println("[" + connectX.getOwnID() + "] REGISTER_NODE event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", approver: "APPROVER_UUID"}
                            String registerJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> registerData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", registerJson, java.util.Map.class);

                            String networkID = (String) registerData.get("network");
                            String nodeID = (String) registerData.get("nodeID");
                            String approver = (String) registerData.get("approver");

                            System.out.println("[REGISTER_NODE] Registering node " + nodeID + " to network " + networkID +
                                             " (approved by " + approver + ")");

                            // Add to registered nodes set (stored in local DataContainer)
                            connectX.dataContainer.networkRegisteredNodes.computeIfAbsent(networkID, k -> new java.util.HashSet<>()).add(nodeID);
                            System.out.println("[REGISTER_NODE] Node " + nodeID + " registered to network " + networkID);
                            System.out.println("[REGISTER_NODE] Total registered nodes: " +
                                connectX.dataContainer.networkRegisteredNodes.get(networkID).size());

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild registeredNodes set during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[REGISTER_NODE] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case PEER_LIST_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] PEER_LIST_REQUEST received from " + nc.iD);
                        try {
                            // Get requester's IP address (from socket if available)
                            String requesterIP = nc.iD; // TODO: Extract actual IP from socket/connection context

                            // Check rate limiting: 3 requests per IP per hour
                            if (isPeerRequestRateLimited(requesterIP)) {
                                System.out.println("[PEER_LIST_REQUEST] Rate limit exceeded for IP " + requesterIP + " (3 per hour)");
                                handledLocally = true;
                                break;
                            }

                            // Record this request for rate limiting
                            recordPeerRequest(requesterIP);

                            // Collect all known peers from PeerDirectory
                            java.util.List<Node> allPeers = new java.util.ArrayList<>();
                            if (PeerDirectory.hv != null) allPeers.addAll(PeerDirectory.hv.values());
                            if (PeerDirectory.seen != null) allPeers.addAll(PeerDirectory.seen.values());
                            if (PeerDirectory.peerCache != null) allPeers.addAll(PeerDirectory.peerCache.values());

                            // Get peer count (30% of known peers or max 10)
                            int knownPeerCount = allPeers.size();
                            int maxPeers = Math.min(10, (int) Math.ceil(knownPeerCount * 0.3));

                            // Select random peers and extract only IP:port
                            java.util.List<String> peerIPs = new java.util.ArrayList<>();

                            // Shuffle and take up to maxPeers
                            java.util.Collections.shuffle(allPeers);
                            for (int i = 0; i < Math.min(maxPeers, allPeers.size()); i++) {
                                Node peer = allPeers.get(i);
                                if (peer.addr != null) {
                                    peerIPs.add(peer.addr); // addr is already in "host:port" format
                                }
                            }

                            // Create response event
                            NetworkEvent responseEvent = new NetworkEvent();
                            responseEvent.eT = EventType.PEER_LIST_RESPONSE.name();
                            responseEvent.iD = java.util.UUID.randomUUID().toString();

                            // Serialize response: {ips: ["192.168.1.100:49152", "10.0.0.5:49153", ...]}
                            java.util.Map<String, Object> response = new java.util.HashMap<>();
                            response.put("ips", peerIPs);
                            String responseJson = ConnectX.serialize("cxJSON1", response);
                            responseEvent.d = responseJson.getBytes("UTF-8");

                            // Set path to send back to requester
                            responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
                            responseEvent.p.cxID = nc.iD;
                            if (ne.p != null) {
                                responseEvent.p.network = ne.p.network;
                                responseEvent.p.scope = ne.p.scope;
                                responseEvent.p.bridge = ne.p.bridge;
                                responseEvent.p.bridgeArg = ne.p.bridgeArg;
                            }

                            System.out.println("[PEER_LIST_REQUEST] Sending " + peerIPs.size() + " peer IPs to " + nc.iD);

                            // Queue response
                            Node requesterNode = PeerDirectory.lookup(nc.iD, true, true);
                            NetworkContainer responseContainer = new NetworkContainer();
                            responseContainer.se = "cxJSON1";
                            responseContainer.s = false;
                            OutputBundle responseBundle = new OutputBundle(responseEvent, requesterNode, null, null, responseContainer);
                            connectX.queueEvent(responseBundle);

                        } catch (Exception e) {
                            System.err.println("[PEER_LIST_REQUEST] Error handling request: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case PEER_LIST_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] PEER_LIST_RESPONSE received from " + nc.iD);
                        try {
                            // Parse response: {ips: ["192.168.1.100:49152", ...]}
                            String responseJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> response =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", responseJson, java.util.Map.class);

                            java.util.List<String> peerIPs = (java.util.List<String>) response.get("ips");

                            System.out.println("[PEER_LIST_RESPONSE] Received " + peerIPs.size() + " peer IPs");

                            // TODO: Contact each IP for Node info/seed
                            // For each IP:
                            //   1. Connect to IP:port
                            //   2. Send NewNode or SEED_REQUEST
                            //   3. Receive Node info and add to PeerDirectory
                            //   4. Cache certificate
                            for (String ipPort : peerIPs) {
                                System.out.println("[PEER_LIST_RESPONSE] Received peer: " + ipPort);
                                // TODO: Implement actual connection logic
                            }

                        } catch (Exception e) {
                            System.err.println("[PEER_LIST_RESPONSE] Error handling response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a known EventType constant, already tried plugins above
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Step 3: Check if event needs relaying to specific target (like isLocal() pattern)
        if (ne.p != null && ne.p.cxID != null && !ne.p.cxID.isEmpty()) {
            // Event has specific target - check if it's for us
            if (!ne.p.cxID.equals(connectX.getOwnID())) {
                // Not for us - relay to target node if we know them
                try {
                    Node targetNode = PeerDirectory.lookup(ne.p.cxID, true, true);
                    if (targetNode != null) {
                        // Create OutputBundle for relay - preserve original sender's signature
                        NetworkContainer relayContainer = new NetworkContainer();
                        relayContainer.se = "cxJSON1";
                        relayContainer.s = false;
                        // Use signedEventBytes to preserve original NetworkEvent signature
                        OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                        connectX.queueEvent(relayBundle);
                        System.out.println("[" + connectX.getOwnID() + "] Relaying event to: " + ne.p.cxID);
                        return true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Step 4: Handle relay logic based on TransmitPref
        // Only relay if we didn't transmit this container (prevent relay loops)
        if (nc != null && nc.iD != null && nc.iD.equals(connectX.getOwnID())) {
            return handledLocally; // We transmitted this, don't relay
        }

        // Get TransmitPref (defaults if null)
        TransmitPref tP = (nc != null && nc.tP != null) ? nc.tP : new TransmitPref();
        String transmitterID = (nc != null && nc.iD != null) ? nc.iD : "";

        // Step 4a: Handle directOnly mode - no relay
        if (tP.directOnly) {
            return handledLocally; // Direct-only, no relaying
        }

        // Step 4b: Handle peerProxy mode - record to blockchain + distribute to all peers
        if (tP.peerProxy) {
            // Try to record to blockchain if we have permissions
            // Use our own ID when recording (we're accepting responsibility for this event)
            try {
                connectX.recordEvent(ne, connectX.getOwnID());
            } catch (Exception ignored) {
                // Permission denied or other error - continue with distribution
            }

            // Distribute to all peers for eventual delivery
            for (Node peer : PeerDirectory.hv.values()) {
                if (!peer.cxID.equals(connectX.getOwnID()) && !peer.cxID.equals(transmitterID)) {
                    try {
                        NetworkContainer relayContainer = new NetworkContainer();
                        relayContainer.se = "cxJSON1";
                        relayContainer.s = false;
                        relayContainer.tP = tP; // Preserve TransmitPref
                        // Use signedEventBytes to preserve original NetworkEvent signature
                        OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                        connectX.queueEvent(relayBundle);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            return handledLocally;
        }

        // Step 4c: Handle peerBroad mode - global cross-network transmission
        if (tP.peerBroad) {
            // Broadcast to all peers across all networks
            for (Node peer : PeerDirectory.hv.values()) {
                if (!peer.cxID.equals(connectX.getOwnID()) && !peer.cxID.equals(transmitterID)) {
                    try {
                        NetworkContainer relayContainer = new NetworkContainer();
                        relayContainer.se = "cxJSON1";
                        relayContainer.s = false;
                        relayContainer.tP = tP; // Preserve TransmitPref
                        // Use signedEventBytes to preserve original NetworkEvent signature
                        OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                        connectX.queueEvent(relayBundle);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            return handledLocally;
        }

        // Step 4d: Default behavior for CXN scope - priority routing through backend + all peers
        // This is the standard mode when TransmitPref flags are all false
        if (ne.p != null && ne.p.scope != null && ne.p.scope.equalsIgnoreCase("CXN") && ne.p.network != null) {
            CXNetwork cxn = connectX.getNetwork(ne.p.network);
            if (cxn != null && cxn.configuration != null && cxn.configuration.backendSet != null) {
                // Send to all peers, with backend getting priority (sent first)
                java.util.Set<String> sentTo = new java.util.HashSet<>();

                // Priority: Send to backend infrastructure first
                for (String backendID : cxn.configuration.backendSet) {
                    if (!backendID.equals(connectX.getOwnID()) && !backendID.equals(transmitterID)) {
                        try {
                            Node backendNode = PeerDirectory.lookup(backendID, true, true);
                            if (backendNode != null) {
                                NetworkContainer relayContainer = new NetworkContainer();
                                relayContainer.se = "cxJSON1";
                                relayContainer.s = false;
                                relayContainer.tP = tP; // Preserve TransmitPref
                                // Use signedEventBytes to preserve original NetworkEvent signature
                                OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                                connectX.queueEvent(relayBundle);
                                sentTo.add(backendID);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // Then send to all other peers (not already sent to)
                // Try hv peers first (high-value/stored), then seen (real-time active connections)
                for (Node peer : PeerDirectory.hv.values()) {
                    if (!peer.cxID.equals(connectX.getOwnID()) &&
                        !peer.cxID.equals(transmitterID) &&
                        !sentTo.contains(peer.cxID)) {
                        try {
                            NetworkContainer relayContainer = new NetworkContainer();
                            relayContainer.se = "cxJSON1";
                            relayContainer.s = false;
                            relayContainer.tP = tP; // Preserve TransmitPref
                            // Use signedEventBytes to preserve original NetworkEvent signature
                            OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                            connectX.queueEvent(relayBundle);
                            sentTo.add(peer.cxID);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // Also try seen peers (may have additional real-time connections not in hv)
                for (Node peer : PeerDirectory.seen.values()) {
                    if (!peer.cxID.equals(connectX.getOwnID()) &&
                        !peer.cxID.equals(transmitterID) &&
                        !sentTo.contains(peer.cxID)) {
                        try {
                            NetworkContainer relayContainer = new NetworkContainer();
                            relayContainer.se = "cxJSON1";
                            relayContainer.s = false;
                            relayContainer.tP = tP; // Preserve TransmitPref
                            // Use signedEventBytes to preserve original NetworkEvent signature
                            OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                            connectX.queueEvent(relayBundle);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        // Step 5: Record to blockchain if scope is CX (must be from authorized sender)
        if (ne.p != null && ne.p.scope != null && ne.p.scope.equalsIgnoreCase("CX")) {
            // CX scope requires authorization from CXNET backendSet or NMI
            if (nc != null && nc.iD != null) {
                connectX.recordEvent(ne, nc.iD);
            }
        }

        return handledLocally;
    }
    public boolean connectNetwork(CXNetwork cxnet) {
        //TODO implement network connection
        return false;
    }
}
