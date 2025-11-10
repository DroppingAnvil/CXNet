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
            Thread ioThread = new Thread(new dev.droppinganvil.v3.io.IOThread(NodeConfig.IO_THREAD_SLEEP, connectX, nodeMesh));
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
            networkEvent = baos.toString("UTF-8");
            ne = (NetworkEvent) ConnectX.deserialize(nc.se, networkEvent, NetworkEvent.class);
            bais.close();
            baoss.close();
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
                    ConnectX.recordEvent(ib.ne);
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
            }
        }
    }
    public boolean fireEvent(NetworkEvent ne) {
        if (ne.p != null && ne.p.scope != null && ne.p.scope.equalsIgnoreCase("CXN")) {
            for (Node n : PeerDirectory.hv.values()) {
                //TODO implement network backend event distribution
            }
        }
        if (ne.eT.equalsIgnoreCase(EventType.PeerFinding.name())) {
            //TODO implement peer finding
        }
        //TODO implement event firing logic
        return false;
    }
    public boolean connectNetwork(CXNetwork cxnet) {
        //TODO implement network connection
        return false;
    }
}
