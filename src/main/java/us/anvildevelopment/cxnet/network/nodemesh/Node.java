package us.anvildevelopment.cxnet.network.nodemesh;

import us.anvildevelopment.cxnet.network.CXPath;
import us.anvildevelopment.util.tools.database.annotations.MemoryOnly;

import java.io.Serializable;

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
     * Stored locally only - NOT serialized to blockchain
     */
    @MemoryOnly
    public String addr;
    /**
     * For future use RESERVED
     */
    public String pr;

    public static boolean validate(Node node) {
        if (node.cxID == null) return false;
        if (node.cxID.length() > 36) return false;
        if (node.publicKey == null) return false;
        return true;
    }
}
