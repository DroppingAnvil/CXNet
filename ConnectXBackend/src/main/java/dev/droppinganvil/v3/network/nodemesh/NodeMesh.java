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

        // Store socket address for error handling and LAN discovery (before socket might be closed)
        String socketAddress = (socket != null && socket.getInetAddress() != null)
            ? socket.getInetAddress().getHostAddress() : null;
        int socketPort = (socket != null) ? socket.getPort() : 0;

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

                // SPECIAL HANDLING FOR NewNode AND CXHELLO EVENTS
                // Strip signature first to check event type
                ByteArrayOutputStream stripBaos = new ByteArrayOutputStream();
                connectX.encryptionProvider.stripSignature(bais, stripBaos);
                String strippedEventJson = stripBaos.toString("UTF-8");
                stripBaos.close(); // Close the strip stream
                NetworkEvent parsedEvent = (NetworkEvent) ConnectX.deserialize(nc.se, strippedEventJson, NetworkEvent.class);

                // Check if this is a NewNode or CXHELLO event (both need public key import before verification)
                if (parsedEvent.eT != null && (parsedEvent.eT.equals("NewNode") || parsedEvent.eT.equals("CXHELLO"))) {
                    System.out.println("[NodeMesh] Processing " + parsedEvent.eT + " event from " + nc.iD);

                    // Import node BEFORE verification (we need the public key)
                    Node importedNode = null;
                    if (parsedEvent.d != null && parsedEvent.d.length > 0) {
                        try {
                            String payloadJson = new String(parsedEvent.d, "UTF-8");
                            Node newNode;

                            // Extract Node from payload based on event type
                            if (parsedEvent.eT.equals("CXHELLO")) {
                                // CXHELLO payload: {"peerID": "...", "port": 12345, "node": {...}}
                                java.util.Map<String, Object> helloData =
                                    (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", payloadJson, java.util.Map.class);

                                // Extract Node object from payload
                                Object nodeObj = helloData.get("node");
                                String nodeJson = ConnectX.serialize("cxJSON1", nodeObj);
                                newNode = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                            } else {
                                // NewNode payload is directly the Node object
                                newNode = (Node) ConnectX.deserialize("cxJSON1", payloadJson, Node.class);
                            }

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
                            System.out.println("Proof that this in an architectural problem you created with bad handling of the CXHELLO pattern @Claude");
                            throw e;
                        } catch (Exception e) {
                            System.err.println("[NodeMesh] Failed to process NewNode: " + e.getMessage());
                            if (importedNode != null) {
                                PeerDirectory.removeNode(importedNode.cxID);
                            }
                            System.out.println("Proof that this in an architectural problem you created with bad handling of the CXHELLO pattern @Claude");
                            throw new DecryptionFailureException();

                        }
                    } else {
                        System.err.println("[NodeMesh] NewNode event has no data");
                        throw new DecryptionFailureException();
                    }

                    // Use the already parsed event
                    ne = parsedEvent;
                    networkEvent = strippedEventJson;
                    System.out.print("Reached event data, data is never used and will be cleaned up by GC shortly");

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
                    //NodeMesh.blacklist.put(socketAddress, "Protocol not respected");
                } else {
                    //NodeMesh.timeout.put(socketAddress, 1000);
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

        // Record source address for peer address mapping (passive discovery)
        // This enables multi-path routing (bridge + direct + LAN)
        if (nc.iD != null && socketAddress != null && socketPort > 0) {
            String peerAddress = socketAddress + ":" + socketPort;
            connectX.dataContainer.recordLocalPeer(nc.iD, peerAddress); // Fallback address
        }

        // For CXHELLO events, also record the LISTENING port from payload with priority
        try {
            EventType et = EventType.valueOf(ne.eT);
            if ((et == EventType.CXHELLO || et == EventType.CXHELLO_RESPONSE) && ne.d != null) {
                String helloJson = new String(ne.d, "UTF-8");
                java.util.Map<String, Object> helloData =
                    (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", helloJson, java.util.Map.class);

                String requestPeerID = (String) helloData.get("peerID");
                Object portObj = helloData.get("port");
                int requestPort = portObj instanceof Integer ? (Integer) portObj : 0;

                // Determine listening address: use port from payload, fallback to node addr, fallback to socket
                String listeningAddress = null;
                if (socketAddress != null && requestPort > 0) {
                    // Use socket IP + port from payload (preferred)
                    listeningAddress = socketAddress + ":" + requestPort;
                } else if (helloData.containsKey("node")) {
                    // Fallback: check if node has addr field
                    Object nodeObj = helloData.get("node");
                    String nodeJson = ConnectX.serialize("cxJSON1", nodeObj);
                    Node node = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                    if (node.addr != null && !node.addr.isEmpty()) {
                        listeningAddress = node.addr;
                    }
                }
                // Final fallback: use socket remote address + port
                if (listeningAddress == null && socketAddress != null && socketPort > 0) {
                    listeningAddress = socketAddress + ":" + socketPort;
                }

                if (listeningAddress != null && requestPeerID != null) {
                    connectX.dataContainer.recordLocalPeer(requestPeerID, listeningAddress, true); // Priority
                    System.out.println("[processNetworkInput] " + et.name() + ": Recorded listening address " + listeningAddress + " for " + requestPeerID.substring(0, 8));
                }
            }
        } catch (Exception e) {
            // Ignore errors in CXHELLO/CXHELLO_RESPONSE parsing - event will still be processed normally
            e.printStackTrace();
        }

        synchronized (connectX.eventQueue) {
            connectX.eventQueue.add(new InputBundle(ne, nc));
        }
    }

    public void processEvent() throws IOException, DecryptionFailureException {
        synchronized (connectX.eventQueue) {
            InputBundle ib = connectX.eventQueue.poll();
            if (ib == null) return; // Queue is empty
            System.out.print(ib.nc);
            System.out.print(ib.ne);
            System.out.print(ib.signedEventBytes);
            EventType et = null;
            try {
                et = EventType.valueOf(ib.ne.eT);
            } catch (Exception ignored) {}
            if (et == null & !ConnectX.sendPluginEvent(ib.ne, ib.ne.eT)) {
                Analytics.addData(AnalyticData.Tear, "Unsupported event - "+ib.ne.eT);
                System.out.print("UNABLE TO PROCESS UNKNOWN EVENT");
                if (NodeConfig.supportUnavailableServices) {
                    // Record unsupported events using the transmitter's ID from NetworkContainer
                    if (ib.nc != null && ib.nc.iD != null) {
                        connectX.Event(ib.ne, ib.nc.iD);
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

                        case CXHELLO:
                            // CXHELLO sends full Node object (like NewNode) - already imported in processNetworkInput
                            // Just send response back with our Node data
                            System.out.println("[" + connectX.getOwnID() + "] CXHELLO request received from " + ib.nc.iD);
                            Node requesterNode = PeerDirectory.lookup(ib.nc.iD, true, true);
                            if (requesterNode != null) {
                                System.out.println("[CXHELLO] Peer discovered from " + ib.nc.iD.substring(0, 8));

                                // Send CXHELLO_RESPONSE with our peerID, port, and Node data
                                java.util.Map<String, Object> responsePayload = new java.util.HashMap<>();
                                responsePayload.put("peerID", connectX.getOwnID());
                                int listeningPort = (in.serverSocket != null) ? in.serverSocket.getLocalPort() : 0;
                                responsePayload.put("port", listeningPort);
                                responsePayload.put("node", connectX.getSelf());

                                String responsePayloadJson = ConnectX.serialize("cxJSON1", responsePayload);

                                NetworkEvent responseEvent = new NetworkEvent();
                                responseEvent.eT = EventType.CXHELLO_RESPONSE.name();
                                responseEvent.iD = java.util.UUID.randomUUID().toString();
                                responseEvent.d = responsePayloadJson.getBytes("UTF-8");

                                // Set path to requester
                                responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
                                responseEvent.p.cxID = ib.nc.iD;
                                responseEvent.p.scope = "CXS"; // Node-to-node

                                NetworkContainer responseContainer = new NetworkContainer();
                                responseContainer.se = "cxJSON1";
                                responseContainer.s = false;

                                // Queue response
                                Node responderNode = requesterNode; // Send back to requester
                                OutputBundle responseBundle = new OutputBundle(responseEvent, responderNode, null, null, responseContainer);
                                connectX.queueEvent(responseBundle);

                                System.out.println("[CXHELLO] Queued CXHELLO_RESPONSE to " + ib.nc.iD.substring(0, 8));
                            }
                            return; // Don't continue to fireEvent

                        case CXHELLO_RESPONSE:
                            // CXHELLO_RESPONSE payload: {"peerID": "...", "port": 12345, "node": {...}}
                            System.out.println("[" + connectX.getOwnID() + "] CXHELLO_RESPONSE received from " + ib.nc.iD);
                            String responsePayloadJson = new String(ib.ne.d, "UTF-8");
                            java.util.Map<String, Object> responseData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", responsePayloadJson, java.util.Map.class);

                            // Extract Node from response payload
                            Object nodeObj = responseData.get("node");
                            String respNodeJson = ConnectX.serialize("cxJSON1", nodeObj);
                            Node responseNode = (Node) ConnectX.deserialize("cxJSON1", respNodeJson, Node.class);

                            // Local address already recorded in processNetworkInput
                            Node existingNode = PeerDirectory.lookup(responseNode.cxID, true, true, connectX.cxRoot, connectX);
                            if (existingNode != null) {
                                connectX.encryptionProvider.cacheCert(existingNode.cxID, true, false);
                                System.out.println("[CXHELLO_RESPONSE] Updated existing node: " + existingNode.cxID.substring(0, 8));
                                return;
                            }
                            PeerDirectory.addNode(responseNode);
                            System.out.println("[CXHELLO_RESPONSE] Imported new node: " + responseNode.cxID.substring(0, 8));
                            return; // Don't continue to fireEvent
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new DecryptionFailureException();
                }

                // After infrastructure handling, fire event to application layer
                fireEvent(ib.ne, ib.nc, ib.signedEventBytes);

                // DEBUG: Log auto-record attempt
                System.out.println("[Auto-Record DEBUG] Event type: " + ib.ne.eT + ", r=" + ib.ne.r +
                    ", nc=" + (ib.nc != null) + ", nc.iD=" + (ib.nc != null ? ib.nc.iD : "null") +
                    ", nc.oD=" + (ib.nc != null ? ib.nc.oD : "null"));

                // Auto-record events with r=true if ORIGINAL sender has permission
                if (ib.ne.r && ib.nc != null && ib.nc.oD != null) {
                    String senderID = ib.nc.oD;
                    String networkID = ib.ne.p != null ? ib.ne.p.network : null;

                    if (networkID != null) {
                        CXNetwork network = connectX.getNetwork(networkID);
                        if (network != null) {
                            // Determine target chain from event type (c1=admin, c2=resources, c3=events)
                            Long chainID = network.networkDictionary.c3;  // Default to c3 for most events
                            if (et != null) {
                                switch (et) {
                                    case REGISTER_NODE:
                                    case BLOCK_NODE:
                                    case UNBLOCK_NODE:
                                    case GRANT_PERMISSION:
                                    case REVOKE_PERMISSION:
                                        chainID = network.networkDictionary.c1;  // Admin events go to c1
                                        break;
                                }
                            }

                            // Check if sender has Record permission for this chain
                            boolean hasPermission = network.checkChainPermission(senderID, dev.droppinganvil.v3.Permission.Record.name(), chainID);

                            if (hasPermission) {
                                // Record event to blockchain
                                boolean recorded = connectX.Event(ib.ne, senderID);
                                if (recorded) {
                                    System.out.println("[Auto-Record] Event " + ib.ne.eT + " from " + senderID.substring(0, 8) + " recorded to chain " + chainID);
                                }
                            } else {
                                System.out.println("[Auto-Record] Event " + ib.ne.eT + " from " + senderID.substring(0, 8) + " NOT recorded - no permission");
                            }
                        }
                    }
                }
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
                            // Parse request to get network ID
                            String requestedNetwork = "CXNET";
                            if (ne.d != null && ne.d.length > 0) {
                                try {
                                    String requestJson = new String(ne.d, "UTF-8");
                                    java.util.Map<String, Object> req = (java.util.Map<String, Object>)
                                        ConnectX.deserialize("cxJSON1", requestJson, java.util.Map.class);
                                    if (req.containsKey("network")) {
                                        requestedNetwork = (String) req.get("network");
                                    }
                                } catch (Exception e) {
                                    // Use default CXNET
                                }
                            }

                            CXNetwork network = connectX.getNetwork(requestedNetwork);
                            if (network != null) {
                                // Create dynamic seed from current peer state
                                dev.droppinganvil.v3.network.Seed dynamicSeed = dev.droppinganvil.v3.network.Seed.fromCurrentPeers();
                                dynamicSeed.seedID = java.util.UUID.randomUUID().toString();
                                dynamicSeed.timestamp = System.currentTimeMillis();
                                dynamicSeed.networkID = requestedNetwork;
                                dynamicSeed.networks = new java.util.ArrayList<>();
                                dynamicSeed.networks.add(network);

                                // Try to load EPOCH's signed seed from disk
                                dev.droppinganvil.v3.network.Seed epochSeed = null;
                                if (network.configuration != null && network.configuration.currentSeedID != null) {
                                    java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                                    java.io.File seedFile = new java.io.File(seedsDir, network.configuration.currentSeedID + ".cxn");

                                    if (seedFile.exists()) {
                                        epochSeed = dev.droppinganvil.v3.network.Seed.load(seedFile);
                                    }
                                }

                                // Determine if this peer is authoritative (NMI/backend)
                                boolean isAuthoritative = false;
                                if (network.configuration != null && network.configuration.backendSet != null) {
                                    isAuthoritative = network.configuration.backendSet.contains(connectX.getOwnID());
                                }

                                // Build response with BOTH seeds
                                java.util.Map<String, Object> response = new java.util.HashMap<>();
                                response.put("dynamicSeed", dynamicSeed);           // Current peer state
                                response.put("epochSeed", epochSeed);               // Signed seed from EPOCH (null if not available)
                                response.put("authoritative", isAuthoritative);     // Is this EPOCH/NMI?
                                response.put("senderID", connectX.getOwnID());

                                // Add blockchain heights for consensus
                                java.util.Map<String, Long> chainHeights = new java.util.HashMap<>();
                                chainHeights.put("c1", network.c1.current != null ? network.c1.current.block : 0L);
                                chainHeights.put("c2", network.c2.current != null ? network.c2.current.block : 0L);
                                chainHeights.put("c3", network.c3.current != null ? network.c3.current.block : 0L);
                                response.put("chainHeights", chainHeights);

                                System.out.println("[SEED] Responding to " + nc.iD.substring(0, 8));
                                System.out.println("[SEED]   Dynamic peers: " + dynamicSeed.hvPeers.size());
                                System.out.println("[SEED]   EPOCH seed: " + (epochSeed != null ? "available" : "none"));
                                System.out.println("[SEED]   Authoritative: " + isAuthoritative);

                                String responseJson = ConnectX.serialize("cxJSON1", response);

                                // Send response
                                NetworkEvent responseEvent = new NetworkEvent();
                                responseEvent.eT = EventType.SEED_RESPONSE.name();
                                responseEvent.iD = java.util.UUID.randomUUID().toString();
                                responseEvent.d = responseJson.getBytes("UTF-8");

                                responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
                                responseEvent.p.cxID = nc.iD;
                                if (ne.p != null) {
                                    responseEvent.p.network = ne.p.network;
                                    responseEvent.p.scope = ne.p.scope;
                                    responseEvent.p.bridge = ne.p.bridge;
                                    responseEvent.p.bridgeArg = ne.p.bridgeArg;
                                }

                                Node requesterNode = PeerDirectory.lookup(nc.iD, true, true);
                                NetworkContainer responseContainer = new NetworkContainer();
                                responseContainer.se = "cxJSON1";
                                responseContainer.s = false;
                                OutputBundle responseBundle = new OutputBundle(responseEvent, requesterNode, null, null, responseContainer);
                                connectX.queueEvent(responseBundle);

                            } else {
                                System.out.println("[SEED] Network " + requestedNetwork + " not found");
                            }
                        } catch (Exception e) {
                            System.err.println("[SEED] Error: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case SEED_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] Seed response received from " + nc.iD);
                        try {
                            // Parse response with consensus metadata
                            String responseJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> response =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", responseJson, java.util.Map.class);

                            // Extract and store response data
                            ConnectX.SeedResponseData responseData = new ConnectX.SeedResponseData();
                            responseData.dynamicSeed = (dev.droppinganvil.v3.network.Seed) response.get("dynamicSeed");
                            responseData.epochSeed = (dev.droppinganvil.v3.network.Seed) response.get("epochSeed");
                            responseData.authoritative = response.containsKey("authoritative") ?
                                (Boolean) response.get("authoritative") : false;
                            responseData.senderID = nc.iD;
                            responseData.timestamp = System.currentTimeMillis();
                            responseData.chainHeights = (java.util.Map<String, Number>) response.get("chainHeights");

                            // Determine target network
                            String targetNetwork = "CXNET";
                            if (responseData.epochSeed != null && responseData.epochSeed.networkID != null) {
                                targetNetwork = responseData.epochSeed.networkID;
                            } else if (responseData.dynamicSeed != null && responseData.dynamicSeed.networkID != null) {
                                targetNetwork = responseData.dynamicSeed.networkID;
                            }

                            System.out.println("[SEED CONSENSUS] Received response for " + targetNetwork);
                            System.out.println("  From: " + nc.iD.substring(0, 8));
                            System.out.println("  Authoritative (EPOCH): " + responseData.authoritative);
                            System.out.println("  Has EPOCH seed: " + (responseData.epochSeed != null));
                            System.out.println("  Has dynamic seed: " + (responseData.dynamicSeed != null));

                            // PRIORITY 1: If this is EPOCH with signed seed, trust immediately
                            if (responseData.authoritative && responseData.epochSeed != null) {
                                System.out.println("[SEED CONSENSUS] ✓ EPOCH responded with signed seed - IMMEDIATE TRUST");
                                applySeedConsensus(connectX, responseData.epochSeed, true,
                                    "EPOCH signed seed (direct from NMI)", targetNetwork);
                                handledLocally = true;
                                break;
                            }

                            // PRIORITY 2: If peer forwarded EPOCH's signed seed, trust it
                            if (responseData.epochSeed != null) {
                                System.out.println("[SEED CONSENSUS] ✓ Peer forwarded EPOCH signed seed - HIGH TRUST");
                                applySeedConsensus(connectX, responseData.epochSeed, true,
                                    "EPOCH signed seed (peer-forwarded by " + nc.iD.substring(0, 8) + ")", targetNetwork);
                                handledLocally = true;
                                break;
                            }

                            // PRIORITY 3: Multi-peer consensus for dynamic seeds
                            // Store response in consensus map
                            if (!connectX.seedConsensusMap.containsKey(targetNetwork)) {
                                connectX.seedConsensusMap.put(targetNetwork, new java.util.concurrent.ConcurrentHashMap<>());
                            }
                            connectX.seedConsensusMap.get(targetNetwork).put(nc.iD, responseData);

                            System.out.println("[SEED CONSENSUS] Stored response from " + nc.iD.substring(0, 8));
                            System.out.println("[SEED CONSENSUS] Responses collected: " +
                                connectX.seedConsensusMap.get(targetNetwork).size());

                            // Trigger consensus if we have enough responses (3) or if EPOCH responded
                            java.util.concurrent.ConcurrentHashMap<String, ConnectX.SeedResponseData> responses =
                                connectX.seedConsensusMap.get(targetNetwork);

                            boolean hasEpochResponse = false;
                            for (ConnectX.SeedResponseData r : responses.values()) {
                                if (r.authoritative) {
                                    hasEpochResponse = true;
                                    break;
                                }
                            }

                            if (responses.size() >= 3 || hasEpochResponse) {
                                System.out.println("[SEED CONSENSUS] Triggering consensus vote...");
                                performSeedConsensus(connectX, targetNetwork, responses);

                                // Clear consensus map after applying
                                connectX.seedConsensusMap.remove(targetNetwork);
                            } else {
                                System.out.println("[SEED CONSENSUS] Waiting for more responses... (" +
                                    responses.size() + "/3)");
                            }

                        } catch (Exception e) {
                            System.err.println("[SEED CONSENSUS] Error: " + e.getMessage());
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

                                // Create response with network ID and status
                                java.util.Map<String, Object> response = new java.util.HashMap<>();
                                response.put("network", networkID);
                                response.put("status", chainStatus);

                                // Create response event
                                NetworkEvent responseEvent = new NetworkEvent();
                                responseEvent.eT = EventType.CHAIN_STATUS_RESPONSE.name();
                                responseEvent.iD = java.util.UUID.randomUUID().toString();

                                String statusJson = ConnectX.serialize("cxJSON1", response);
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
                            java.util.Map<String, Object> response =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", statusJson, java.util.Map.class);

                            String networkID = (String) response.get("network");
                            java.util.Map<String, Number> chainStatus = (java.util.Map<String, Number>) response.get("status");

                            System.out.println("[CHAIN_STATUS] Remote chain heights for " + networkID + " from " + nc.iD.substring(0, 8) + ":");
                            System.out.println("  c1: " + chainStatus.get("c1"));
                            System.out.println("  c2: " + chainStatus.get("c2"));
                            System.out.println("  c3: " + chainStatus.get("c3"));

                            // Store response for multipeer verification
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Check if this response is from NMI (always trust NMI)
                                boolean isNMI = network.configuration.backendSet != null &&
                                               network.configuration.backendSet.contains(nc.iD);

                                if (isNMI) {
                                    System.out.println("[CHAIN_STATUS] Response from NMI/Backend - TRUSTED");
                                    // NMI response is authoritative, initiate sync immediately
                                    initiateSyncFromPeer(connectX, network, networkID, chainStatus, nc.iD);
                                } else {
                                    // Peer response - store for multipeer verification
                                    System.out.println("[CHAIN_STATUS] Response from peer - storing for verification");
                                    // TODO: Implement multipeer consensus mechanism
                                    // For now, skip peer-only responses (require NMI or multiple peer consensus)
                                }
                            }
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

                            // Get network ID from block's first event (all events in block should be from same network)
                            String networkID = null;
                            Long chainID = null;
                            for (NetworkEvent event : block.networkEvents.values()) {
                                if (event.p != null && event.p.network != null) {
                                    networkID = event.p.network;
                                    break;
                                }
                            }

                            if (networkID == null) {
                                System.err.println("[BLOCK_RESPONSE] Cannot determine network ID from block events");
                                handledLocally = true;
                                break;
                            }

                            // Get network and determine which chain this block belongs to
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network == null) {
                                System.err.println("[BLOCK_RESPONSE] Network not found: " + networkID);
                                handledLocally = true;
                                break;
                            }

                            // Determine target chain based on block's chain ID from metadata
                            dev.droppinganvil.v3.edge.NetworkRecord targetChain = null;
                            if (block.chain != null) {
                                chainID = block.chain;
                                if (chainID.equals(network.networkDictionary.c1)) targetChain = network.c1;
                                else if (chainID.equals(network.networkDictionary.c2)) targetChain = network.c2;
                                else if (chainID.equals(network.networkDictionary.c3)) targetChain = network.c3;
                            }

                            if (targetChain == null) {
                                System.err.println("[BLOCK_RESPONSE] Cannot determine target chain for block");
                                handledLocally = true;
                                break;
                            }

                            // Add block to local blockchain in memory
                            targetChain.blockMap.put(block.block, block);
                            System.out.println("[BLOCK_RESPONSE] Added block " + block.block + " to chain " + chainID + " in memory");

                            // Update current block pointer if this is the latest block
                            if (targetChain.current == null || block.block > targetChain.current.block) {
                                targetChain.current = block;
                                System.out.println("[BLOCK_RESPONSE] Updated current block to " + block.block);
                            }

                            // Save block to disk for persistence
                            try {
                                connectX.blockchainPersistence.saveBlock(networkID, chainID, block);
                                System.out.println("[BLOCK_RESPONSE] Saved block " + block.block + " to disk");
                            } catch (Exception e) {
                                System.err.println("[BLOCK_RESPONSE] Failed to save block to disk: " + e.getMessage());
                            }

                            // Queue state-modifying events for processing to rebuild state
                            int stateEvents = 0;
                            int skippedEvents = 0;
                            for (NetworkEvent event : block.networkEvents.values()) {
                                if (event.executeOnSync) {
                                    // Queue event for processing through existing framework
                                    dev.droppinganvil.v3.network.events.NetworkContainer eventContainer =
                                        new dev.droppinganvil.v3.network.events.NetworkContainer();
                                    eventContainer.se = "cxJSON1";
                                    eventContainer.s = false;
                                    eventContainer.iD = nc.iD; // Mark as coming from the peer who sent the block

                                    InputBundle ib = new InputBundle(event, eventContainer);
                                    connectX.eventQueue.add(ib);

                                    stateEvents++;
                                    System.out.println("[BLOCK_SYNC] Queued state event: " + event.eT);
                                } else {
                                    // Skip ephemeral events (messages, pings, etc.)
                                    skippedEvents++;
                                }
                            }

                            System.out.println("[BLOCK_SYNC] Block " + block.block + " processed:");
                            System.out.println("  - Added to chain " + chainID);
                            System.out.println("  - Saved to disk");
                            System.out.println("  - Queued " + stateEvents + " state events");
                            System.out.println("  - Skipped " + skippedEvents + " ephemeral events");

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
                    case GRANT_PERMISSION:
                        System.out.println("[" + connectX.getOwnID() + "] GRANT_PERMISSION event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", permission: "Record", chain: 3, priority: 10}
                            String grantJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> grantData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", grantJson, java.util.Map.class);

                            String networkID = (String) grantData.get("network");
                            String nodeID = (String) grantData.get("nodeID");
                            String permission = (String) grantData.get("permission");
                            Object chainObj = grantData.get("chain");
                            Long chainID = chainObj instanceof Integer ? ((Integer) chainObj).longValue() : (Long) chainObj;
                            Object priorityObj = grantData.get("priority");
                            int priority = priorityObj instanceof Integer ? (Integer) priorityObj : 10;

                            System.out.println("[GRANT_PERMISSION] Granting " + permission + " permission to node " + nodeID +
                                             " on network " + networkID + " chain " + chainID + " (priority: " + priority + ")");

                            // Get the network
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Create permission entry
                                String permKey = permission + "-" + chainID;
                                us.anvildevelopment.util.tools.permissions.Entry entry =
                                    new us.anvildevelopment.util.tools.permissions.BasicEntry(permKey, true, priority);

                                // Add to network permissions
                                java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> nodePerms =
                                    network.networkPermissions.permissionSet.computeIfAbsent(nodeID, k -> new java.util.HashMap<>());
                                nodePerms.put(permKey, entry);

                                System.out.println("[GRANT_PERMISSION] Permission granted successfully");
                            } else {
                                System.err.println("[GRANT_PERMISSION] Network not found: " + networkID);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild permissions during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[GRANT_PERMISSION] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case REVOKE_PERMISSION:
                        System.out.println("[" + connectX.getOwnID() + "] REVOKE_PERMISSION event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", permission: "Record", chain: 3}
                            String revokeJson = new String(ne.d, "UTF-8");
                            java.util.Map<String, Object> revokeData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", revokeJson, java.util.Map.class);

                            String networkID = (String) revokeData.get("network");
                            String nodeID = (String) revokeData.get("nodeID");
                            String permission = (String) revokeData.get("permission");
                            Object chainObj = revokeData.get("chain");
                            Long chainID = chainObj instanceof Integer ? ((Integer) chainObj).longValue() : (Long) chainObj;

                            System.out.println("[REVOKE_PERMISSION] Revoking " + permission + " permission from node " + nodeID +
                                             " on network " + networkID + " chain " + chainID);

                            // Get the network
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Remove permission entry
                                String permKey = permission + "-" + chainID;
                                java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> nodePerms =
                                    network.networkPermissions.permissionSet.get(nodeID);
                                if (nodePerms != null) {
                                    nodePerms.remove(permKey);
                                    if (nodePerms.isEmpty()) {
                                        network.networkPermissions.permissionSet.remove(nodeID);
                                    }
                                    System.out.println("[REVOKE_PERMISSION] Permission revoked successfully");
                                } else {
                                    System.out.println("[REVOKE_PERMISSION] Node had no permissions to revoke");
                                }
                            } else {
                                System.err.println("[REVOKE_PERMISSION] Network not found: " + networkID);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild permissions during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[REVOKE_PERMISSION] Error handling event: " + e.getMessage());
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
                        relayContainer.oD = nc.oD; // Preserve original sender
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
                connectX.Event(ne, connectX.getOwnID());
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
                        relayContainer.oD = nc.oD; // Preserve original sender
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
            //System.out.println("[RELAY DEBUG] peerBroad=true, broadcasting to " + PeerDirectory.hv.size() + " peers");
            // Broadcast to all peers across all networks
            for (Node peer : PeerDirectory.hv.values()) {
                if (!peer.cxID.equals(connectX.getOwnID()) && !peer.cxID.equals(transmitterID)) {
                    try {
                        NetworkContainer relayContainer = new NetworkContainer();
                        relayContainer.se = "cxJSON1";
                        relayContainer.s = false;
                        relayContainer.tP = tP; // Preserve TransmitPref
                        relayContainer.oD = nc.oD; // Preserve original sender (must be set)
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
                                relayContainer.oD = nc.oD; // Preserve original sender
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
                            relayContainer.oD = nc.oD; // Preserve original sender
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
                            relayContainer.oD = nc.oD; // Preserve original sender
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
                connectX.Event(ne, nc.iD);
            }
        }

        return handledLocally;
    }
    public boolean connectNetwork(CXNetwork cxnet) {
        //TODO implement network connection
        return false;
    }

    /**
     * Initiate blockchain sync from a trusted peer (NMI/Backend)
     * Compares local and remote chain heights and requests missing blocks
     */
    private void initiateSyncFromPeer(ConnectX connectX, CXNetwork network, String networkID,
                                     java.util.Map<String, Number> remoteChainStatus, String peerID) {
        try {
            long remoteC1 = remoteChainStatus.get("c1").longValue();
            long remoteC2 = remoteChainStatus.get("c2").longValue();
            long remoteC3 = remoteChainStatus.get("c3").longValue();

            long localC1 = network.c1.current != null ? network.c1.current.block : -1;
            long localC2 = network.c2.current != null ? network.c2.current.block : -1;
            long localC3 = network.c3.current != null ? network.c3.current.block : -1;

            System.out.println("[CHAIN_SYNC] Local chain heights:");
            System.out.println("  c1: " + localC1);
            System.out.println("  c2: " + localC2);
            System.out.println("  c3: " + localC3);

            Node remotePeer = PeerDirectory.lookup(peerID, true, true);
            if (remotePeer == null) {
                System.err.println("[CHAIN_SYNC] Cannot sync - peer not found: " + peerID);
                return;
            }

            // Sync c1 (Admin chain) first - most critical for state
            if (remoteC1 > localC1) {
                System.out.println("[CHAIN_SYNC] c1 is behind, requesting " + (remoteC1 - localC1) + " blocks");
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c1, localC1 + 1, remoteC1, remotePeer);
            }

            // Sync c2 (Resources chain)
            if (remoteC2 > localC2) {
                System.out.println("[CHAIN_SYNC] c2 is behind, requesting " + (remoteC2 - localC2) + " blocks");
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c2, localC2 + 1, remoteC2, remotePeer);
            }

            // Sync c3 (Events chain)
            if (remoteC3 > localC3) {
                System.out.println("[CHAIN_SYNC] c3 is behind, requesting " + (remoteC3 - localC3) + " blocks");
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c3, localC3 + 1, remoteC3, remotePeer);
            }

            if (remoteC1 <= localC1 && remoteC2 <= localC2 && remoteC3 <= localC3) {
                System.out.println("[CHAIN_SYNC] Local chains are up to date!");
            }
        } catch (Exception e) {
            System.err.println("[CHAIN_SYNC] Error initiating sync: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Request a range of missing blocks from a peer
     * Sends BLOCK_REQUEST for each block in the range
     */
    private void requestMissingBlocks(ConnectX connectX, String networkID, Long chainID,
                                     long startBlock, long endBlock, Node remotePeer) {
        try {
            for (long blockNum = startBlock; blockNum <= endBlock; blockNum++) {
                // Create BLOCK_REQUEST event
                java.util.Map<String, Object> request = new java.util.HashMap<>();
                request.put("network", networkID);
                request.put("chain", chainID);
                request.put("block", blockNum);

                String requestJson = ConnectX.serialize("cxJSON1", request);

                NetworkEvent requestEvent = new NetworkEvent();
                requestEvent.eT = EventType.BLOCK_REQUEST.name();
                requestEvent.iD = java.util.UUID.randomUUID().toString();
                requestEvent.d = requestJson.getBytes("UTF-8");

                // Set path to target peer
                requestEvent.p = new dev.droppinganvil.v3.network.CXPath();
                requestEvent.p.cxID = remotePeer.cxID;
                requestEvent.p.network = networkID;
                requestEvent.p.scope = "CXS"; // Node-to-node
                // Copy bridge info from peer if available
                if (remotePeer.addr != null && remotePeer.addr.startsWith("cxHTTP1:")) {
                    String[] parts = remotePeer.addr.split(":", 2);
                    requestEvent.p.bridge = parts[0];
                    requestEvent.p.bridgeArg = parts[1];
                }

                // Queue request
                NetworkContainer requestContainer = new NetworkContainer();
                requestContainer.se = "cxJSON1";
                requestContainer.s = false;
                OutputBundle requestBundle = new OutputBundle(requestEvent, remotePeer, null, null, requestContainer);
                connectX.queueEvent(requestBundle);

                System.out.println("[CHAIN_SYNC] Requested block " + blockNum + " from chain " + chainID);
            }
        } catch (Exception e) {
            System.err.println("[CHAIN_SYNC] Error requesting blocks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Apply a seed after consensus decision
     * Saves EPOCH seeds to disk, adds peers, caches certificates, registers networks
     */
    private static void applySeedConsensus(ConnectX connectX, dev.droppinganvil.v3.network.Seed seed,
                                          boolean isEpochSeed, String consensusReason, String targetNetwork) {
        try {
            System.out.println("[SEED CONSENSUS] Applying seed: " + seed.seedID);
            System.out.println("[SEED CONSENSUS] Reason: " + consensusReason);
            System.out.println("[SEED CONSENSUS] Networks: " + seed.networks.size());
            System.out.println("[SEED CONSENSUS] Peers: " + seed.hvPeers.size());
            System.out.println("[SEED CONSENSUS] Certificates: " + seed.certificates.size());

            // Save EPOCH signed seeds to disk so this peer can forward them
            if (isEpochSeed) {
                java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                if (!seedsDir.exists()) {
                    seedsDir.mkdirs();
                }
                java.io.File seedFile = new java.io.File(seedsDir, seed.seedID + ".cxn");
                seed.save(seedFile);
                System.out.println("[SEED CONSENSUS] ✓ Saved EPOCH seed: " + seedFile.getName());
                System.out.println("[SEED CONSENSUS] ✓ This peer can now forward EPOCH seed to others!");
            }

            // Add peers to directory
            int peersAdded = 0;
            for (dev.droppinganvil.v3.network.nodemesh.Node peer : seed.hvPeers) {
                try {
                    PeerDirectory.addNode(peer);
                    peersAdded++;
                } catch (Exception e) {
                    // Ignore duplicate peer errors
                }
            }
            System.out.println("[SEED CONSENSUS] ✓ Added " + peersAdded + " peers to directory");

            // Apply seed to ConnectX (registers networks)
            seed.apply(connectX);
            System.out.println("[SEED CONSENSUS] ✓ Networks registered");

            // Cache certificates
            int certsAdded = 0;
            for (java.util.Map.Entry<String, String> cert : seed.certificates.entrySet()) {
                try {
                    connectX.encryptionProvider.cacheCert(cert.getKey(), false, false);
                    certsAdded++;
                } catch (Exception e) {
                    // Ignore cert errors
                }
            }
            System.out.println("[SEED CONSENSUS] ✓ Cached " + certsAdded + " certificates");
            System.out.println("[SEED CONSENSUS] ✓✓✓ Network " + targetNetwork + " is READY!");

        } catch (Exception e) {
            System.err.println("[SEED CONSENSUS] Error applying seed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Perform multi-peer consensus voting on dynamic seeds
     * Compares chain heights, detects conflicts, applies majority consensus
     * If conflicts detected, requests fresh seed from EPOCH as tiebreaker
     */
    private static void performSeedConsensus(ConnectX connectX, String targetNetwork,
                                            java.util.concurrent.ConcurrentHashMap<String, ConnectX.SeedResponseData> responses) {
        try {
            System.out.println("[SEED CONSENSUS] === MULTI-PEER VOTING ===");
            System.out.println("[SEED CONSENSUS] Responses: " + responses.size());

            // Priority 1: Check if EPOCH responded (always trust EPOCH)
            for (ConnectX.SeedResponseData r : responses.values()) {
                if (r.authoritative && r.dynamicSeed != null) {
                    System.out.println("[SEED CONSENSUS] ✓ EPOCH dynamic seed found - TRUSTING");
                    applySeedConsensus(connectX, r.dynamicSeed, false,
                        "EPOCH dynamic seed (authoritative)", targetNetwork);
                    return;
                }
            }

            // Priority 2: Compare chain heights across peers for consensus
            java.util.Map<String, Integer> heightVotes = new java.util.HashMap<>();
            for (ConnectX.SeedResponseData r : responses.values()) {
                if (r.chainHeights != null) {
                    // Create signature from chain heights
                    String heightSig = "c1:" + r.chainHeights.get("c1") +
                                     ",c2:" + r.chainHeights.get("c2") +
                                     ",c3:" + r.chainHeights.get("c3");
                    heightVotes.put(heightSig, heightVotes.getOrDefault(heightSig, 0) + 1);
                }
            }

            System.out.println("[SEED CONSENSUS] Chain height voting:");
            for (java.util.Map.Entry<String, Integer> vote : heightVotes.entrySet()) {
                System.out.println("[SEED CONSENSUS]   " + vote.getKey() + " → " + vote.getValue() + " votes");
            }

            // Find majority consensus (>50% agreement)
            String majorityHeights = null;
            int maxVotes = 0;
            for (java.util.Map.Entry<String, Integer> vote : heightVotes.entrySet()) {
                if (vote.getValue() > maxVotes) {
                    maxVotes = vote.getValue();
                    majorityHeights = vote.getKey();
                }
            }

            double consensusPercent = (double) maxVotes / responses.size();
            System.out.println("[SEED CONSENSUS] Majority: " + maxVotes + "/" + responses.size() +
                             " (" + String.format("%.0f%%", consensusPercent * 100) + ")");

            if (consensusPercent >= 0.51) {
                // Majority consensus achieved - use seed from majority peer
                System.out.println("[SEED CONSENSUS] ✓ CONSENSUS REACHED (" +
                                 String.format("%.0f%%", consensusPercent * 100) + " agreement)");

                for (ConnectX.SeedResponseData r : responses.values()) {
                    if (r.chainHeights != null) {
                        String heightSig = "c1:" + r.chainHeights.get("c1") +
                                         ",c2:" + r.chainHeights.get("c2") +
                                         ",c3:" + r.chainHeights.get("c3");
                        if (heightSig.equals(majorityHeights) && r.dynamicSeed != null) {
                            applySeedConsensus(connectX, r.dynamicSeed, false,
                                "Peer consensus (" + String.format("%.0f%%", consensusPercent * 100) +
                                " agreement) from " + r.senderID.substring(0, 8), targetNetwork);
                            return;
                        }
                    }
                }
            } else {
                // No consensus - conflict detected
                System.out.println("[SEED CONSENSUS] ✗ CONSENSUS FAILED - Peer conflict detected");
                System.out.println("[SEED CONSENSUS] No majority agreement (only " +
                                 String.format("%.0f%%", consensusPercent * 100) + "%)");
                System.out.println("[SEED CONSENSUS] → Requesting authoritative seed from EPOCH...");

                // TODO: Send SEED_REQUEST directly to EPOCH as tiebreaker
                // For now, use fallback: load signed EPOCH seed from disk if available
                java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                if (seedsDir.exists()) {
                    java.io.File[] seedFiles = seedsDir.listFiles((dir, name) -> name.endsWith(".cxn"));
                    if (seedFiles != null && seedFiles.length > 0) {
                        // Load most recent seed
                        java.io.File latestSeed = seedFiles[0];
                        for (java.io.File f : seedFiles) {
                            if (f.lastModified() > latestSeed.lastModified()) {
                                latestSeed = f;
                            }
                        }
                        System.out.println("[SEED CONSENSUS] Using fallback: Signed EPOCH seed from disk");
                        dev.droppinganvil.v3.network.Seed epochSeed =
                            dev.droppinganvil.v3.network.Seed.load(latestSeed);
                        applySeedConsensus(connectX, epochSeed, true,
                            "EPOCH signed seed (disk fallback - peer conflict)", targetNetwork);
                        return;
                    }
                }

                System.err.println("[SEED CONSENSUS] ✗ Cannot resolve - no EPOCH seed available");
                System.err.println("[SEED CONSENSUS] Network may be compromised or EPOCH offline");
            }

        } catch (Exception e) {
            System.err.println("[SEED CONSENSUS] Voting error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
