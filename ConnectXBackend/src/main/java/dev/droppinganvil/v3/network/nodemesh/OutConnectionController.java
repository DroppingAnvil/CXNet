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
                Node n;
                if (PeerDirectory.lan.containsKey(out.ne.p.cxID)) {
                    n = PeerDirectory.lan.get(out.ne.p.cxID);
                } else {
                    n = PeerDirectory.lookup(out.ne.p.cxID, true, true);
                }

                // Try direct P2P connection if node has address
                if (n != null && n.addr != null && !n.addr.isEmpty()) {
                    try {
                        // Check if addr is a bridge address (starts with protocol:)
                        if (n.addr.contains(":") && !n.addr.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:.*")) {
                            // This is a bridge address like "cxHTTP1:https://..."
                            String[] parts = n.addr.split(":", 2);
                            String bridgeProtocol = parts[0];
                            String bridgeEndpoint = parts[1];

                            // Use bridge provider (per-instance)
                            dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                                connectXAPI.getBridgeProvider(bridgeProtocol);
                            if (bridge != null) {
                                System.out.println("[OutConnection] Using " + bridgeProtocol + " bridge to reach " + out.ne.p.cxID);
                                // Create path with bridge info
                                dev.droppinganvil.v3.network.CXPath bridgePath = new dev.droppinganvil.v3.network.CXPath();
                                bridgePath.bridgeArg = bridgeEndpoint;
                                bridge.transmitEvent(bridgePath, cryptNetworkContainer);
                                return;
                            } else {
                                System.err.println("[OutConnection] Bridge provider " + bridgeProtocol + " not available");
                            }
                        } else {
                            // Standard P2P address (IP:port)
                            String[] addr = n.addr.split(":");
                            Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                            s.getOutputStream().write(cryptNetworkContainer);
                            s.close();
                            return;
                        }
                    } catch (Exception e) {
                        System.err.println("[OutConnection] P2P transmission failed: " + e.getMessage());
                        // Fall through to bridge fallback
                    }
                }

                // Bridge fallback: If direct P2P failed or node not found, try CXPath bridge
                if (out.ne.p.bridge != null && out.ne.p.bridgeArg != null) {
                    dev.droppinganvil.v3.network.nodemesh.bridge.BridgeProvider bridge =
                        connectXAPI.getBridgeProvider(out.ne.p.bridge);
                    if (bridge != null) {
                        System.out.println("[OutConnection] Falling back to " + out.ne.p.bridge + " bridge for " + out.ne.p.cxID);
                        bridge.transmitEvent(out.ne.p, cryptNetworkContainer);
                        return;
                    } else {
                        System.err.println("[OutConnection] Bridge provider " + out.ne.p.bridge + " not available");
                        throw new Exception("Cannot reach node " + out.ne.p.cxID + " - no P2P address and bridge unavailable");
                    }
                }

                // No way to reach the node
                throw new Exception("Cannot reach node " + out.ne.p.cxID + " - no P2P address or bridge information");
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
                            try {
                                if (n.addr != null) {
                                    String[] addr = n.addr.split(":");
                                    Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                                    s.getOutputStream().write(cryptNetworkContainer);
                                    s.close();
                                }
                            } catch (Exception e) {
                                // Peer unavailable, continue to next
                                continue;
                            }
                        }
                    }
                }
            }
        }


    }

}
