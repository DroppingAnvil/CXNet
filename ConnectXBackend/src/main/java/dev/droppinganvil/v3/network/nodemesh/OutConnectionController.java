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
        cryptEvent = out.prev;
        if (cryptEvent == null) {
            if (nc.s) {
                //E2E Event Layer
                assert out.n != null;
                //TODO
            } else {
                nc.iD = connectXAPI.getOwnID();
                if (NodeConfig.revealVersion) nc.v = NodeConfig.cxV;
                cryptEvent = connectXAPI.signObject(out.ne, NetworkEvent.class, nc.se).toByteArray();
            }
        }
        nc.e = cryptEvent;
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
                String[] addr = n.addr.split(":");
                Socket s = new Socket(addr[0], Integer.parseInt(addr[1]));
                s.getOutputStream().write(cryptNetworkContainer);
                s.close();
                return;
            }
            if (out.ne.p.scope.equalsIgnoreCase("CXN")) {
                // Network backend transmission using CXPath.network
                CXNetwork cxn = ConnectX.getNetwork(out.ne.p.network);
                if (cxn != null) {
                    for (String s : cxn.configuration.backendSet) {
                        Node n = PeerDirectory.lookup(s, true, true);
                        String[] addr = n.addr.split(":");
                        Socket so = new Socket(addr[0], Integer.parseInt(addr[1]));
                        so.getOutputStream().write(cryptNetworkContainer);
                        so.close();
                    }
                }
            }
        }


    }

}
