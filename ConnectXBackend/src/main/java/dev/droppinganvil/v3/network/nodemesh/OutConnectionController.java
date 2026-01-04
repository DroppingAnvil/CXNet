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

        // Record to blockchain if this is a new CXN event (not relay, not CXS)
        // Use the same signed blob being transmitted to ensure blockchain consistency
        if (out.prev == null && out.ne.p != null && out.ne.p.scope != null &&
            out.ne.p.scope.equalsIgnoreCase("CXN") && out.ne.p.chainID != null) {
            try {
                boolean recorded = connectXAPI.Event(out.ne, connectXAPI.getOwnID(), cryptEvent);
                if (recorded) {
                    System.out.println("[Blockchain] Recorded event " + out.ne.eT + " to chain " + out.ne.p.chainID);
                } else {
                    System.err.println("[Blockchain] Event() returned false for " + out.ne.eT);
                }
            } catch (Exception e) {
                System.err.println("[Blockchain] Failed to record event during transmission: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // Debug: Why didn't we record?
            if (out.ne.eT != null && out.ne.eT.equals("MESSAGE")) {
                System.out.println("[Blockchain-Debug] MESSAGE not recorded: prev=" + (out.prev == null) +
                    ", hasPath=" + (out.ne.p != null) +
                    ", hasScope=" + (out.ne.p != null && out.ne.p.scope != null) +
                    ", scope=" + (out.ne.p != null ? out.ne.p.scope : "null") +
                    ", hasChainID=" + (out.ne.p != null && out.ne.p.chainID != null) +
                    ", chainID=" + (out.ne.p != null ? out.ne.p.chainID : "null"));
            }
        }

        // Initialize TransmitPref if not set (defaults to peerBroad = true)
        if (nc.tP == null) {
            nc.tP = new TransmitPref();
        }

        // Sign the NetworkContainer (outer layer) with our signature as the transmitter
        byte[] cryptNetworkContainer = connectXAPI.signObject(nc, NetworkContainer.class, nc.se).toByteArray();
        if (out.ne.p != null && out.ne.p.scope != null) {
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

                // Use getAllAddresses() for priority-ordered multi-source lookup (LAN-Direct prioritized)
                java.util.List<String> addresses = PeerDirectory.getAllAddresses(out.ne.p.cxID, connectXAPI);
                StringBuilder failedRoutes = new StringBuilder();
                int routeAttempts = 0;

                for (String address : addresses) {
                    routeAttempts++;
                    try {
                        if (address.contains(":") && !address.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:.*")) {
                            // This is a bridge address like "cxHTTP1:https://..."
                            String[] parts = address.split(":", 2);
                            String bridgeProtocol = parts[0];
                            String bridgeEndpoint = parts[1];

                            System.out.println("[ROUTE-TRY " + routeAttempts + "/" + addresses.size() + "] " +
                                out.ne.p.cxID.substring(0, 8) + " via Bridge: " + bridgeProtocol);

                            dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                                connectXAPI.getBridgeProvider(bridgeProtocol);
                            if (bridge != null) {
                                dev.droppinganvil.v3.network.CXPath bridgePath = new dev.droppinganvil.v3.network.CXPath();
                                bridgePath.bridgeArg = bridgeEndpoint;
                                bridge.transmitEvent(bridgePath, cryptNetworkContainer);
                                sentViaAnyRoute = true;
                                routesUsed.append(bridgeProtocol).append("+");
                                System.out.println("[ROUTE-SUCCESS] " + out.ne.p.cxID.substring(0, 8) +
                                    " via " + bridgeProtocol);
                                break; // Success - stop trying
                            } else {
                                failedRoutes.append(bridgeProtocol).append("(no-provider),");
                            }
                        } else {
                            // Direct/LAN address (IP:port)
                            System.out.println("[ROUTE-TRY " + routeAttempts + "/" + addresses.size() + "] " +
                                out.ne.p.cxID.substring(0, 8) + " via LAN-Direct: " + address);

                            String[] addr = address.split(":");
                            Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                            s.getOutputStream().write(cryptNetworkContainer);
                            s.close();
                            sentViaAnyRoute = true;
                            routesUsed.append("LAN-Direct+");
                            System.out.println("[ROUTE-SUCCESS] " + out.ne.p.cxID.substring(0, 8) +
                                " via LAN-Direct: " + address);

                            // Deprioritize any failed routes that came before this successful one
                            if (routeAttempts > 1 && connectXAPI.dataContainer != null) {
                                connectXAPI.dataContainer.deprioritizeFailedRoute(out.ne.p.cxID, address, routeAttempts - 1);
                            }

                            break; // Success - stop trying
                        }
                    } catch (Exception e) {
                        // This route failed - log and try next one
                        String routeDesc = address.contains("://") ? address.split(":")[0] : address;
                        failedRoutes.append(routeDesc).append("(").append(e.getMessage()).append("),");
                        System.out.println("[ROUTE-FAIL] " + out.ne.p.cxID.substring(0, 8) +
                            " via " + routeDesc + ": " + e.getMessage());
                        continue;
                    }
                }

                // Log all failed routes if transmission failed completely
                if (!sentViaAnyRoute && failedRoutes.length() > 0) {
                    System.out.println("[ROUTE-ALL-FAILED] " + out.ne.p.cxID.substring(0, 8) +
                        " - Tried " + routeAttempts + " routes: " + failedRoutes);
                }

                // Fallback: Try CXPath bridge from event path if getAllAddresses didn't work
                if (!sentViaAnyRoute && out.ne.p.bridge != null && out.ne.p.bridgeArg != null) {
                    try {
                        dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                            connectXAPI.getBridgeProvider(out.ne.p.bridge);
                        if (bridge != null) {
                            bridge.transmitEvent(out.ne.p, cryptNetworkContainer);
                            sentViaAnyRoute = true;
                            routesUsed.append(out.ne.p.bridge).append("-Path+");
                        }
                    } catch (Exception e) {
                        // Route failed
                    }
                }

                if (!sentViaAnyRoute) {
                    throw new Exception("Cannot reach node " + out.ne.p.cxID + " - all routes failed");
                }

                // Remove trailing "+"
                if (routesUsed.length() > 0) {
                    routesUsed.setLength(routesUsed.length() - 1);
                }
                String cxidDisplay = (out.ne.p.cxID != null && out.ne.p.cxID.length() >= 8) ? out.ne.p.cxID.substring(0, 8) : (out.ne.p.cxID != null ? out.ne.p.cxID : "NULL");
                System.out.println("[Multi-Path] Sent to " + cxidDisplay + " via: " + routesUsed);
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
                    // IMPORTANT: Always try ALL peers even if some are unreachable
                    // Other nodes might have different paths to unreachable peers
                    int totalPeers = 0;
                    int successfulPeers = 0;
                    int failedPeers = 0;

                    for (Node n : PeerDirectory.hv.values()) {
                        // Don't send to ourselves
                        if (n.cxID != null && !n.cxID.equals(connectXAPI.getOwnID())) {
                            totalPeers++;
                            // MULTI-PATH ROUTING: Use getAllAddresses() to check all sources
                            java.util.List<String> addresses = PeerDirectory.getAllAddresses(n.cxID, connectXAPI);
                            StringBuilder routes = new StringBuilder();
                            boolean sentToThisPeer = false;

                            int routeAttempts = 0;
                            for (String address : addresses) {
                                routeAttempts++;
                                try {
                                    // Check if this is a bridge address (contains protocol prefix)
                                    if (address.contains(":") && !address.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:.*")) {
                                        // Bridge address like "cxHTTP1:https://..."
                                        String[] parts = address.split(":", 2);
                                        String bridgeProtocol = parts[0];
                                        String bridgeEndpoint = parts[1];

                                        dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                                            connectXAPI.getBridgeProvider(bridgeProtocol);
                                        if (bridge != null) {
                                            dev.droppinganvil.v3.network.CXPath peerPath = new dev.droppinganvil.v3.network.CXPath();
                                            peerPath.bridgeArg = bridgeEndpoint;
                                            bridge.transmitEvent(peerPath, cryptNetworkContainer);
                                            routes.append(bridgeProtocol);
                                            sentToThisPeer = true;
                                            break;  // Successfully sent, no need to try other routes
                                        }
                                    } else {
                                        // Direct/LAN address (IP:port)
                                        String[] addr = address.split(":");
                                        Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                                        s.getOutputStream().write(cryptNetworkContainer);
                                        s.close();
                                        routes.append("LAN-Direct");
                                        sentToThisPeer = true;

                                        // Deprioritize any failed routes that came before this successful one
                                        if (routeAttempts > 1 && connectXAPI.dataContainer != null) {
                                            connectXAPI.dataContainer.deprioritizeFailedRoute(n.cxID, address, routeAttempts - 1);
                                        }

                                        break;  // Successfully sent, no need to try other routes
                                    }
                                } catch (Exception e) {
                                    // This route failed, continue trying other routes for this peer
                                    // DON'T stop - try all addresses before giving up
                                }
                            }

                            // Log results for this peer
                            if (sentToThisPeer) {
                                System.out.println("[Multi-Path CXN] ✓ " + n.cxID.substring(0, 8) + " via: " + routes);
                                successfulPeers++;
                            } else {
                                // ALL routes failed for this peer - log warning but CONTINUE to next peer
                                System.out.println("[Multi-Path CXN] ✗ " + n.cxID.substring(0, 8) + " UNREACHABLE (tried " + addresses.size() + " routes)");
                                failedPeers++;
                            }
                        }
                    }

                    // Summary logging for CXN broadcast
                    if (totalPeers > 0) {
                        String eventType = (out.ne != null && out.ne.eT != null) ? out.ne.eT : "UNKNOWN";
                        String eventId = (out.ne != null && out.ne.iD != null) ? out.ne.iD.substring(0, 8) : "UNKNOWN";
                        System.out.println("[CXN Broadcast] " + eventType + " (" + eventId + "...) sent to " +
                            successfulPeers + "/" + totalPeers + " peers (" + failedPeers + " unreachable)");
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
