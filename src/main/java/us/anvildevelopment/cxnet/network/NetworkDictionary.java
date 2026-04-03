/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network;

import us.anvildevelopment.util.tools.permissions.BasicPermissionContainer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class NetworkDictionary implements Serializable {
    /**
     * Network Master Identity public key
     */
    public String nmi;
    public String networkID;
    public Long networkCreate;
    /**
     * When this object was last updated
     */
    public Long lastUpdate;
    /**
     * Base chain IDs
     */
    public Long c1;
    public Long c2;
    public Long c3;
    /**
     * Public keys of authorized backend nodes, in perspective of network master
     */
    public List<String> backendSet = new ArrayList<>();
    /**
     * Object to outline basic authentication
     */
    public BasicPermissionContainer networkPermissions;

    /**
     * Controls how seed blobs for this network are verified when distributed by peers.
     *
     * <ul>
     *   <li>{@code false} (default) -- seed blob must be verified against the NMI or a node
     *       in the backendSet. Only EPOCH / authoritative backends can distribute seeds.</li>
     *   <li>{@code true} -- seed blob may be signed by any known peer. The receiving node
     *       verifies against the sender's cached public key. Suitable for informal peer
     *       networks where any member can relay the seed.</li>
     * </ul>
     *
     * This flag is embedded inside the signed seed blob itself, so a relaying peer
     * cannot forge it without breaking the NMI's signature on the original seed.
     */
    public boolean dynamicSeed = false;

}
