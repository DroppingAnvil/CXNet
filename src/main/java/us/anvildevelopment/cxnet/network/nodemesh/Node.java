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
     * Timestamp (epoch millis) set immediately before this Node is self-signed.
     * Part of the signed payload, so it cannot be altered without invalidating the signature.
     * Set only by the originating node in ConnectX.signSelfNode(). Used to pick the most
     * recent of several independently-verified copies of the same node (e.g. multiple peers
     * responding to a lookup with different cosigned copies).
     *
     * Cosigning note: EPOCH or a trusted CXNET backend may wrap an already-self-signed Node
     * blob with an additional outer signature for distribution to peers who haven't met the
     * originator directly. The cosigner only adds an outer signature over the existing bytes;
     * it never recreates, re-serializes, or alters the inner self-signed Node JSON, and a
     * cosigned copy can never replace an existing, independently self-signed entry for the
     * same cxID in PeerDirectory. Cosigning exists solely to bootstrap trust for nodes the
     * local peer has not yet met directly. A direct self-signed update from the real
     * originator always takes precedence once received.
     */
    public long signedAt;

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
