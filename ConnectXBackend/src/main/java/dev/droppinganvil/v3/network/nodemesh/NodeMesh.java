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

        Object o = connectX.encryptionProvider.decrypt(is, baos);
        String networkContainer = baos.toString("UTF-8");
        try {
            nc = (NetworkContainer) ConnectX.deserialize("cxJSON1", networkContainer, NetworkContainer.class);
            if (nc.iD != null) ConnectX.checkSafety(nc.iD);
            assert nc.v != null;
            if (!ConnectX.isProviderPresent(nc.se)) {
                socket.close();
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
                o1 = connectX.encryptionProvider.verifyAndStrip(bais, baoss, nc.iD);
            }
            networkEvent = baoss.toString("UTF-8");  // Use baoss (output from verifyAndStrip)
            ne = (NetworkEvent) ConnectX.deserialize(nc.se, networkEvent, NetworkEvent.class);
            bais.close();
            baoss.close();

            // SECURITY: Validate CXNET and CX scope messages
            // Only CXNET backendSet or NMI can send CXNET or CX-scoped messages
            if (ne.p != null && ne.p.scope != null &&
                (ne.p.scope.equalsIgnoreCase("CXNET") || ne.p.scope.equalsIgnoreCase("CX"))) {
                CXNetwork cxnet = ConnectX.getNetwork("CXNET");
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
                    socket.close();
                    Analytics.addData(AnalyticData.Tear, "Unauthorized " + ne.p.scope + " message from " + nc.iD);
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (!NodeConfig.devMode) {
                if (NodeMesh.timeout.containsKey(socket.getInetAddress().getHostAddress())) {
                    NodeMesh.blacklist.put(socket.getInetAddress().getHostAddress(), "Protocol not respected");
                } else {
                    NodeMesh.timeout.put(socket.getInetAddress().getHostAddress(), 1000);
                }
            }
            socket.close();
            return;
        }
        synchronized (ConnectX.eventQueue) {
            ConnectX.eventQueue.add(new InputBundle(ne, nc));
        }
    }

    public void processEvent() throws IOException, DecryptionFailureException {
        synchronized (ConnectX.eventQueue) {
            InputBundle ib = ConnectX.eventQueue.poll();
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
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ByteArrayInputStream bais = new ByteArrayInputStream(ib.nc.e);
                            Object o = connectX.encryptionProvider.decrypt(bais, baos);
                            Node node = (Node) ConnectX.deserialize("cxJSON1", baos.toString("UTF-8"), Node.class);
                            Node node1 = PeerDirectory.lookup(node.cxID, true, true, connectX.cxRoot, connectX);
                            if (node1 != null) {
                                connectX.encryptionProvider.cacheCert(node1.cxID, true, false);
                                return;
                            }
                            PeerDirectory.addNode(node);
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
                        synchronized (ConnectX.outputQueue) {
                            ConnectX.outputQueue.add(relayBundle);
                        }
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
                        synchronized (ConnectX.outputQueue) {
                            ConnectX.outputQueue.add(relayBundle);
                        }
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
                        synchronized (ConnectX.outputQueue) {
                            ConnectX.outputQueue.add(relayBundle);
                        }
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
            CXNetwork cxn = ConnectX.getNetwork(ne.p.network);
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
                                synchronized (ConnectX.outputQueue) {
                                    ConnectX.outputQueue.add(relayBundle);
                                }
                                sentTo.add(backendID);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // Then send to all other peers (not already sent to)
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
                            synchronized (ConnectX.outputQueue) {
                                ConnectX.outputQueue.add(relayBundle);
                            }
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
