package dev.droppinganvil.v3.network.nodemesh;

import dev.droppinganvil.v3.Configuration;
import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.edge.DataContainer;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.events.NetworkEvent;
import dev.droppinganvil.v3.network.events.NetworkContainer;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Iterator;

/**
 * LAN Scanner for active local peer discovery
 * Scans the local network to find peers on the same subnet
 * Enables direct P2P communication and firewall traversal
 */
public class LANScanner {
    private final ConnectX connectX;
    private final int primaryPort;
    private final AtomicInteger discovered = new AtomicInteger(0);
    private volatile boolean scanning = false;

    // Track CXHELLO sockets for cleanup
    private final java.util.Map<Socket, Long> activeCXHELLOSockets = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SOCKET_TIMEOUT_MS = 5000; // Close sockets after 5 seconds

    // Port range to scan (default: 49152-49162 = 10 ports)
    private static final int PORT_RANGE_START = 49152;
    private static final int PORT_RANGE_END = 49162;

    // Common ConnectX ports to check
    private static final int[] COMMON_PORTS = {
        49152, 49153, 49154, 49155, 49156,  // Default range
        8080, 8081, 8082, 8083, 8084,        // HTTP test ports
        50000, 50001, 50002                  // Alternative range
    };

    public LANScanner(ConnectX connectX, int primaryPort) {
        this.connectX = connectX;
        this.primaryPort = primaryPort;

        // Start automatic socket cleanup thread
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000); // Check every second
                    cleanupCXHELLOSockets();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setName("LAN-Scanner-Cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * Get local IP address (non-loopback, IPv4)
     */
    public static String getLocalIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                // Skip loopback and inactive interfaces
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    // Only IPv4 addresses, skip loopback
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("[LAN Scanner] Error getting local IP: " + e.getMessage());
        }

        return null;
    }

    /**
     * Get subnet mask for an IP address (CIDR notation)
     */
    public static int getSubnetMask(String ipAddress) {
        try {
            InetAddress localHost = InetAddress.getByName(ipAddress);
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(localHost);

            if (networkInterface != null) {
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    if (address.getAddress().equals(localHost)) {
                        return address.getNetworkPrefixLength();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[LAN Scanner] Error getting subnet mask: " + e.getMessage());
        }

        // Default to /24 (255.255.255.0)
        return 24;
    }

    /**
     * Calculate IP range from base IP and subnet mask
     */
    public static List<String> getIPRange(String baseIP, int subnetMask) {
        List<String> ipList = new ArrayList<>();

        try {
            String[] parts = baseIP.split("\\.");
            if (parts.length != 4) return ipList;

            int ipInt = 0;
            for (int i = 0; i < 4; i++) {
                ipInt |= (Integer.parseInt(parts[i]) << (24 - (8 * i)));
            }

            // Calculate network address
            int mask = 0xFFFFFFFF << (32 - subnetMask);
            int network = ipInt & mask;
            int hostBits = 32 - subnetMask;
            int numHosts = (1 << hostBits) - 2; // Exclude network and broadcast

            // Generate all host IPs in range (limit to 254 for performance)
            int maxToScan = Math.min(numHosts, 254);
            for (int i = 1; i <= maxToScan; i++) {
                int hostIP = network + i;
                String ip = String.format("%d.%d.%d.%d",
                    (hostIP >> 24) & 0xFF,
                    (hostIP >> 16) & 0xFF,
                    (hostIP >> 8) & 0xFF,
                    hostIP & 0xFF
                );
                ipList.add(ip);
            }
        } catch (Exception e) {
            System.err.println("[LAN Scanner] Error calculating IP range: " + e.getMessage());
        }

        return ipList;
    }

    /**
     * Check if a host is reachable on a specific port
     */
    private boolean isHostReachable(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 200); // 200ms timeout per port
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Scan an IP for ConnectX peers on all common ports
     * Returns the port number if found, or -1 if not found
     */
    private int findPeerPort(String ip) {
        String localIP = getLocalIP();

        // First check the primary port (most likely) on remote IPs only
        // On our own IP or localhost, we'll check it via COMMON_PORTS loop (which allows skipping our own port)
        boolean isOwnIP = ip.equals(localIP) || ip.equals("127.0.0.1");
        if (!isOwnIP) {  // Only check primaryPort on remote IPs
            if (isHostReachable(ip, primaryPort)) {
                return primaryPort;
            }
        }

        // Then check all common ports
        for (int port : COMMON_PORTS) {
            // Skip our own port on our own IP or localhost
            if ((ip.equals(localIP) || ip.equals("127.0.0.1")) && port == primaryPort) {
                continue;
            }

            if (isHostReachable(ip, port)) {
                return port;
            }
        }

        return -1; // Not found on any port
    }

    /**
     * Send CXHELLO to discover a peer
     * IMPORTANT: Uses NEWNODE manual pattern for direct transmission to unknown peers
     * This bypasses buildEvent() API because we don't have peer ID yet (can't use CXS routing)
     * Instead relies on direct transmission fallback in OutConnectionController (line 252-266)
     * which checks out.n != null && out.n.addr != null
     *
     * Sends Node as SIGNED BLOB to enable .cxi persistence on receiver
     */
    private void sendCXHELLO(String targetIP, int targetPort) {
        try {
            // Sign our Node object for .cxi persistence on receiver
            String nodeJson = ConnectX.serialize("cxJSON1", connectX.getSelf());
            java.io.ByteArrayInputStream nodeInput = new java.io.ByteArrayInputStream(nodeJson.getBytes("UTF-8"));
            java.io.ByteArrayOutputStream signedNodeOutput = new java.io.ByteArrayOutputStream();
            connectX.encryptionProvider.sign(nodeInput, signedNodeOutput);
            nodeInput.close();
            byte[] signedNodeBlob = signedNodeOutput.toByteArray();
            signedNodeOutput.close();

            // Create CXHELLO payload using CXHello data structure
            // Include peer's preferred address if available (for NAT/public IP scenarios)
            String peerAddress = (connectX.getSelf() != null) ? connectX.getSelf().addr : null;
            dev.droppinganvil.v3.network.events.CXHello helloPayload =
                new dev.droppinganvil.v3.network.events.CXHello(connectX.getOwnID(), primaryPort, signedNodeBlob, peerAddress);

            String payloadJson = ConnectX.serialize("cxJSON1", helloPayload);

            // Sign the CXHELLO payload for consistency with CXHELLO_RESPONSE
            java.io.ByteArrayInputStream payloadInput = new java.io.ByteArrayInputStream(payloadJson.getBytes("UTF-8"));
            java.io.ByteArrayOutputStream signedPayloadOutput = new java.io.ByteArrayOutputStream();
            connectX.encryptionProvider.sign(payloadInput, signedPayloadOutput);
            payloadInput.close();
            byte[] signedPayload = signedPayloadOutput.toByteArray();
            signedPayloadOutput.close();

            // Manually create NetworkEvent (like NEWNODE bootstrap pattern)
            NetworkEvent helloEvent = new NetworkEvent(EventType.CXHELLO, signedPayload);
            helloEvent.eT = EventType.CXHELLO.name();
            helloEvent.iD = java.util.UUID.randomUUID().toString();
            // DON'T set event.p (Permission/CXPath) - leave null for direct transmission fallback

            // Create target node with address only (no peer ID known yet)
            Node targetNode = new Node();
            targetNode.addr = targetIP + ":" + targetPort;

            // Manually create NetworkContainer
            NetworkContainer nc = new NetworkContainer();
            nc.se = "cxJSON1";
            nc.s = false;  // Not E2E encrypted
            nc.iD = connectX.getSelf().cxID;  // Set sender ID (like NewNode)

            // Create OutputBundle directly (bypassing buildEvent) - follows NEWNODE pattern
            OutputBundle bundle = new OutputBundle(helloEvent, targetNode, null, null, nc);
            connectX.queueEvent(bundle);

            System.out.println("[LAN Scanner] Queued CXHELLO for " + targetIP + ":" + targetPort);

        } catch (Exception e) {
            // Failed to send, skip (normal for non-ConnectX hosts)
            System.err.println("[LAN Scanner] Failed to send CXHELLO to " + targetIP + ":" + targetPort + " - " + e.getMessage());
        }
    }

    /**
     * Cleanup old CXHELLO sockets that have timed out
     */
    private void cleanupCXHELLOSockets() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Socket, Long>> iterator = activeCXHELLOSockets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Socket, Long> entry = iterator.next();
            if (now - entry.getValue() > SOCKET_TIMEOUT_MS) {
                try {
                    entry.getKey().close();
                } catch (Exception ignored) {}
                iterator.remove();
            }
        }
    }

    /**
     * Scan the local network for peers
     */
    public void scanNetwork() {
        if (scanning) {
            System.out.println("[LAN Scanner] Scan already in progress");
            return;
        }

        Thread scanThread = new Thread(() -> {
            scanning = true;
            discovered.set(0);

            if (NodeConfig.DEBUG) System.out.println("\n[LAN Scanner] ========================================");
            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Starting active LAN peer discovery...");
            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] ========================================");

            String localIP = getLocalIP();
            if (localIP == null) {
                System.err.println("[LAN Scanner] Unable to determine local IP address");
                scanning = false;
                return;
            }

            int subnetMask = getSubnetMask(localIP);
            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Local IP: " + localIP + "/" + subnetMask);

            List<String> ipRange = getIPRange(localIP, subnetMask);

            // IMPORTANT: Also scan localhost (127.0.0.1) for same-machine peers
            // This enables testing and supports multiple instances on same server
            if (!ipRange.contains("127.0.0.1")) {
                ipRange.add(0, "127.0.0.1");  // Add at beginning for priority
                if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Added localhost (127.0.0.1) to scan range");
            }

            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Scanning " + ipRange.size() + " addresses...");
            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Checking " + (COMMON_PORTS.length + 1) + " ports per host");
            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Primary port: " + primaryPort);

            AtomicInteger progress = new AtomicInteger(0);
            List<Thread> scanThreads = new ArrayList<>();

            for (String ip : ipRange) {
                Thread scanner = new Thread(() -> {
                    // Scan all common ports for this IP (findPeerPort already skips our own port)
                    int foundPort = findPeerPort(ip);

                    if (foundPort != -1) {
                        System.out.println("[LAN Scanner] ✓ Found active peer at " + ip + ":" + foundPort);
                        //TODO is foundPort correct????
                        sendCXHELLO(ip, foundPort);
                        discovered.incrementAndGet();
                    }

                    int p = progress.incrementAndGet();
                    if (p % 25 == 0 || p == ipRange.size()) {
                        if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Progress: " + p + "/" + ipRange.size() +
                                         " (" + discovered.get() + " discovered)");
                    }
                });

                scanThreads.add(scanner);
                scanner.start();

                // Limit concurrent threads (reduced because we're checking multiple ports per host)
                if (scanThreads.size() >= 20) {
                    for (Thread t : scanThreads) {
                        try {
                            t.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    scanThreads.clear();
                }
            }

            // Wait for remaining threads
            for (Thread t : scanThreads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            System.out.println("\n[LAN Scanner] ========================================");
            System.out.println("[LAN Scanner] Scan complete!");
            System.out.println("[LAN Scanner] Found " + discovered.get() + " active peers on local network");
            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Total peers in DataContainer: " +
                             connectX.dataContainer.getAllLocalPeerAddresses().size());
            System.out.println("[LAN Scanner] ========================================\n");

            // Set ConnectX state to READY - signals that P2P discovery has completed
            connectX.state = dev.droppinganvil.v3.State.READY;
            if (NodeConfig.DEBUG) System.out.println("[LAN Scanner] Network state set to READY");

            scanning = false;
        });

        scanThread.setName("LAN-Scanner-Thread");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    /**
     * Broadcast CXHELLO to local network (UDP broadcast)
     * Alternative to full scan - faster but less reliable
     */
    public void broadcastHello() {
        Thread broadcastThread = new Thread(() -> {
            try {
                System.out.println("[LAN Scanner] Broadcasting CXHELLO to local network...");

                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);

                // Create CXHELLO payload
                Map<String, Object> payload = new HashMap<>();
                payload.put("peerID", connectX.getOwnID());
                payload.put("port", primaryPort);
                payload.put("broadcast", true);

                String payloadJson = ConnectX.serialize("cxJSON1", payload);
                byte[] data = payloadJson.getBytes("UTF-8");

                // Broadcast to subnet
                String localIP = getLocalIP();
                if (localIP != null) {
                    String[] parts = localIP.split("\\.");
                    String broadcastIP = parts[0] + "." + parts[1] + "." + parts[2] + ".255";

                    InetAddress group = InetAddress.getByName(broadcastIP);

                    // Broadcast to all common ports
                    for (int port : COMMON_PORTS) {
                        DatagramPacket packet = new DatagramPacket(data, data.length, group, port);
                        socket.send(packet);
                    }

                    System.out.println("[LAN Scanner] Broadcast sent to " + broadcastIP + " on " + COMMON_PORTS.length + " ports");
                }

                socket.close();
            } catch (Exception e) {
                System.err.println("[LAN Scanner] Broadcast failed: " + e.getMessage());
            }
        });

        broadcastThread.setName("LAN-Broadcast-Thread");
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    /**
     * Get number of discovered peers
     */
    public int getDiscoveredCount() {
        return discovered.get();
    }

    /**
     * Check if scan is in progress
     */
    public boolean isScanning() {
        return scanning;
    }
}
