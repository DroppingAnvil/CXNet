package us.anvildevelopment.cxnet.network.nodemesh;

import us.anvildevelopment.cxnet.network.CXPath;
import us.anvildevelopment.util.tools.database.annotations.MemoryOnly;

import java.io.Serializable;
import java.util.List;

public class Node implements Serializable {
    /**
     * Path to node for server use cases
     */
    public CXPath path;
    /**
     * Device's network account id
     */
    public String cxID;
    /**
     * Node public key
     */
    public String publicKey;
    /**
     * TCP/IP address in format host:port for direct P2P connections
     * This is an
     */
    @MemoryOnly
    public String addr;
    /**
     * For future use RESERVED
     */
    public String pr;

    /**
     * Network IDs this node is currently a member of.
     * Included primarily for routing purposes.
     * NOTE: This is not required advertising self networks is optional
     */
    public List<String> networks;

    public static boolean validate(Node node) {
        if (node.cxID == null) return false;
        if (node.cxID.length() > 36) return false;
        if (node.publicKey == null) return false;
        return true;
    }
}
