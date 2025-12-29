package dev.droppinganvil.v3.network.nodemesh;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.network.CXNetwork;
import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.events.NetworkEvent;

import java.net.Socket;

public class OutConnectionController {
    public ConnectX connectXAPI;

    public OutConnectionController(ConnectX api) {
        connectXAPI = api;
    }

    public void transmitEvent(OutputBundle out) throws Exception {
        //TODO streams
        NetworkContainer nc = out.nc;
        byte[] cryptEvent;

        // Debug: Log routing info
        if (out.ne != null && out.ne.eT != null && out.ne.eT.contains("HELLO")) {
            System.out.println("[TX-DEBUG] Event=" + out.ne.eT + ", ne.p=" + (out.ne.p != null ? out.ne.p.scope : "null") +
                ", out.n=" + (out.n != null ? out.n.addr : "null"));
        }

        // IMPORTANT: Always set transmitter ID before signing NetworkContainer
        // This is required even when relaying (out.prev != null) so recipients can verify the signature
        nc.iD = connectXAPI.getOwnID();
        if (NodeConfig.revealVersion) nc.v = NodeConfig.cxV;

        cryptEvent = out.prev;
        if (cryptEvent == null) {
            // New message (not a relay): Set original sender ID and generate event ID
            nc.oD = connectXAPI.getOwnID();  // We are the original sender

            // ALWAYS generate unique event ID for security
            // Event IDs are MANDATORY for duplicate detection and replay prevention
            if (out.ne.iD == null || out.ne.iD.isEmpty()) {
                out.ne.iD = java.util.UUID.randomUUID().toString();
            }

            if (nc.s) {
                //E2E Event Layer
                assert out.n != null;
                //TODO
            } else {
                // Sign the NetworkEvent (inner layer) with our signature
                cryptEvent = connectXAPI.signObject(out.ne, NetworkEvent.class, nc.se).toByteArray();
            }
        }
        // If out.prev != null, we're relaying - use the preserved signed event bytes
        // This maintains the original sender's signature on the NetworkEvent
        // Note: oD should already be set in the relay container, but if not set it from the event
        nc.e = cryptEvent;

        // Initialize TransmitPref if not set (defaults to peerBroad = true)
        if (nc.tP == null) {
            nc.tP = new TransmitPref();
        }

        // Sign the NetworkContainer (outer layer) with our signature as the transmitter
        byte[] cryptNetworkContainer = connectXAPI.signObject(nc, NetworkContainer.class, nc.se).toByteArray();
        if (out.ne.p != null && out.ne.p.scope != null && out.ne.p.scope.equalsIgnoreCase("CXNET")) {
            for (Node n : PeerDirectory.hv.values()) {
                try {
                    if (n.addr != null) {
                        String[] addr = n.addr.split(":");
                        Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                        s.getOutputStream().write(cryptNetworkContainer);
                        s.close();
                    }
                    if (n.path != null) {
                        //TODO
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        } else if (out.ne.p != null && out.ne.p.scope != null) {
            if (out.ne.p.scope.equalsIgnoreCase("CXS")) {
                // Single peer transmission using CXPath.cxID
                // MULTI-PATH ROUTING: Try ALL available routes for redundancy
                boolean sentViaAnyRoute = false;
                StringBuilder routesUsed = new StringBuilder();

                Node n;
                if (PeerDirectory.lan.containsKey(out.ne.p.cxID)) {
                    n = PeerDirectory.lan.get(out.ne.p.cxID);
                } else {
                    n = PeerDirectory.lookup(out.ne.p.cxID, true, true);
                }

                // ROUTE 1: Try HTTP bridge from node address
                if (n != null && n.addr != null && !n.addr.isEmpty()) {
                    try {
                        if (n.addr.contains(":") && !n.addr.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:.*")) {
                            // This is a bridge address like "cxHTTP1:https://..."
                            String[] parts = n.addr.split(":", 2);
                            String bridgeProtocol = parts[0];
                            String bridgeEndpoint = parts[1];

                            dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                                connectXAPI.getBridgeProvider(bridgeProtocol);
                            if (bridge != null) {
                                dev.droppinganvil.v3.network.CXPath bridgePath = new dev.droppinganvil.v3.network.CXPath();
                                bridgePath.bridgeArg = bridgeEndpoint;
                                bridge.transmitEvent(bridgePath, cryptNetworkContainer);
                                sentViaAnyRoute = true;
                                routesUsed.append(bridgeProtocol).append("+");
                            }
                        } else {
                            // Standard P2P address (IP:port) from PeerDirectory
                            String[] addr = n.addr.split(":");
                            Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                            s.getOutputStream().write(cryptNetworkContainer);
                            s.close();
                            sentViaAnyRoute = true;
                            routesUsed.append("P2P-Directory+");
                        }
                    } catch (Exception e) {
                        // Route failed, try next
                    }
                }

                // ROUTE 2: Try CXPath bridge from event path
                if (out.ne.p.bridge != null && out.ne.p.bridgeArg != null) {
                    try {
                        dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                            connectXAPI.getBridgeProvider(out.ne.p.bridge);
                        if (bridge != null) {
                            bridge.transmitEvent(out.ne.p, cryptNetworkContainer);
                            sentViaAnyRoute = true;
                            routesUsed.append(out.ne.p.bridge).append("-Path+");
                        }
                    } catch (Exception e) {
                        // Route failed, try next
                    }
                }

                // ROUTE 3: Try local/direct address from DataContainer (LAN discovery)
                String localAddress = connectXAPI.dataContainer.getLocalPeerAddress(out.ne.p.cxID);
                System.out.println("[OutController-DEBUG] Looking up local address for " + out.ne.p.cxID.substring(0, 8) + ": " + localAddress);
                if (localAddress != null && !localAddress.isEmpty()) {
                    try {
                        String[] addr = localAddress.split(":");
                        Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                        s.getOutputStream().write(cryptNetworkContainer);
                        s.close();
                        sentViaAnyRoute = true;
                        routesUsed.append("LAN-Direct+");
                        System.out.println("[OutController] LAN-Direct SUCCESS to " + localAddress);
                    } catch (Exception e) {
                        System.out.println("[OutController] LAN-Direct FAILED to " + localAddress + ": " + e.getMessage());
                        // Route failed, try next
                    }
                }

                if (!sentViaAnyRoute) {
                    throw new Exception("Cannot reach node " + out.ne.p.cxID + " - all routes failed");
                }

                // Remove trailing "+"
                if (routesUsed.length() > 0) {
                    routesUsed.setLength(routesUsed.length() - 1);
                }
                System.out.println("[Multi-Path] Sent to " + out.ne.p.cxID.substring(0, 8) + " via: " + routesUsed);
            }
            if (out.ne.p.scope.equalsIgnoreCase("CXN")) {
                // CXN scope: Network transmission with TransmitPref support
                CXNetwork cxn = connectXAPI.getNetwork(out.ne.p.network);

                if (nc.tP != null && nc.tP.directOnly && cxn != null) {
                    // directOnly: Direct backend transmission for speed (no relay)
                    // Use case: Fast point-to-point when backend is known to be online
                    for (String s : cxn.configuration.backendSet) {
                        try {
                            Node n = PeerDirectory.lookup(s, true, true);
                            if (n != null && n.addr != null) {
                                String[] addr = n.addr.split(":");
                                Socket so = new Socket(addr[0], Integer.parseInt(addr[1]));
                                so.getOutputStream().write(cryptNetworkContainer);
                                so.close();
                            }
                        } catch (Exception e) {
                            // Backend unavailable, continue
                            continue;
                        }
                    }
                } else {
                    // Standard/peerProxy/peerBroad: P2P mesh transmission
                    // Send to all available peers, relay logic handles:
                    // - Backend priority (fireEvent)
                    // - Blockchain recording (peerProxy)
                    // - Global distribution (peerBroad)
                    for (Node n : PeerDirectory.hv.values()) {
                        // Don't send to ourselves
                        if (n.cxID != null && !n.cxID.equals(connectXAPI.getOwnID())) {
                            // MULTI-PATH ROUTING: Try ALL routes for this peer
                            StringBuilder routes = new StringBuilder();

                            // ROUTE 1: Try bridge if available
                            if (n.addr != null && !n.addr.isEmpty() && n.addr.contains(":") &&
                                !n.addr.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:.*")) {
                                try {
                                    // This is a bridge address like "cxHTTP1:https://..."
                                    String[] parts = n.addr.split(":", 2);
                                    String bridgeProtocol = parts[0];
                                    String bridgeEndpoint = parts[1];

                                    dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                                        connectXAPI.getBridgeProvider(bridgeProtocol);
                                    if (bridge != null) {
                                        dev.droppinganvil.v3.network.CXPath peerPath = new dev.droppinganvil.v3.network.CXPath();
                                        peerPath.bridgeArg = bridgeEndpoint;
                                        bridge.transmitEvent(peerPath, cryptNetworkContainer);
                                        routes.append(bridgeProtocol).append("+");
                                    }
                                } catch (Exception e) {
                                    // Route failed
                                }
                            }

                            // ROUTE 2: Try standard P2P address if available
                            if (n.addr != null && !n.addr.isEmpty() && n.addr.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:.*")) {
                                try {
                                    String[] addr = n.addr.split(":");
                                    Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                                    s.getOutputStream().write(cryptNetworkContainer);
                                    s.close();
                                    routes.append("P2P+");
                                } catch (Exception e) {
                                    // Route failed
                                }
                            }

                            // ROUTE 3: Try local address from DataContainer
                            String localAddr = connectXAPI.dataContainer.getLocalPeerAddress(n.cxID);
                            if (localAddr != null && !localAddr.isEmpty()) {
                                try {
                                    String[] addr = localAddr.split(":");
                                    Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                                    s.getOutputStream().write(cryptNetworkContainer);
                                    s.close();
                                    routes.append("LAN+");
                                } catch (Exception e) {
                                    // Route failed
                                }
                            }

                            // Log routes used
                            if (routes.length() > 0) {
                                routes.setLength(routes.length() - 1); // Remove trailing "+"
                                System.out.println("[Multi-Path CXN] " + n.cxID.substring(0, 8) + " via: " + routes);
                            }
                        }
                    }
                }
            }
        } else if (out.n != null && out.n.addr != null && !out.n.addr.isEmpty()) {
            // Special case: Direct transmission to address (for CXHELLO discovery)
            // Used when we have a target address but no peer ID yet
            System.out.println("[OutController] Attempting direct transmission to " + out.n.addr + " (" + cryptNetworkContainer.length + " bytes)");
            try {
                String[] addr = out.n.addr.split(":");
                Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                java.io.OutputStream os = s.getOutputStream();
                os.write(cryptNetworkContainer);
                os.flush();
                // Wait for receiver to read data before closing (receiver timeout is 400ms)
                Thread.sleep(500);
                s.close();
                System.out.println("[OutController] Direct transmission SUCCESS to " + out.n.addr);
            } catch (Exception e) {
                // Failed to send (normal for discovery - host may not be ConnectX peer)
                System.out.println("[OutController] Direct transmission FAILED to " + out.n.addr + ": " + e.getMessage());
            }
        } else {
            // No routing info - log for debugging
            if (out.ne != null && out.ne.eT != null && (out.ne.eT.equals("CXHELLO") || out.ne.eT.equals("CXHELLO_RESPONSE"))) {
                System.out.println("[OutController] WARNING: No routing for " + out.ne.eT +
                    " - Permission: " + (out.ne.p != null ? out.ne.p.scope : "null") +
                    ", Node: " + (out.n != null && out.n.addr != null ? out.n.addr : "null"));
            }
        }


    }

}
