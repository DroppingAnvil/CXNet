package dev.droppinganvil.v3.network.nodemesh;

import dev.droppinganvil.v3.network.CXPath;
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
        assert node.cxID != null;
        if (node.cxID.length() > 36) return false;
        assert node.publicKey != null;
        return true;
    }
}
