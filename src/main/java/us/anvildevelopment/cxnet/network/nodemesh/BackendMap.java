/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.cxnet.network.CXNetwork;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Preferred backend nodes per network: which of a network's backends this node should actually
 * talk to right now.
 *
 * Distinct from {@code CXNetwork.configuration.backendSet}, which is the authoritative membership
 * list and stays the thing CXB scope fans out over. This is a routing preference, recomputed from
 * live reachability, and deliberately small: three backends is enough redundancy to survive one
 * going away without turning every backend call into a broadcast.
 *
 * Networks are tracked LRU to 30. A node that has drifted through many networks should not hold
 * routing state for all of them forever.
 */
public class BackendMap implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(BackendMap.class);

    /** Networks tracked before the least recently used is dropped. */
    public static final int MAX_NETWORKS = 30;

    /** Priority backends held per network. A count cap, not a score threshold. */
    public static final int MAX_BACKENDS_PER_NETWORK = 3;

    /**
     * networkID -> chosen backend cxIDs, in preference order.
     *
     * LinkedHashMap in access order gives the network-level LRU directly. Guarded by the map's
     * own monitor rather than being concurrent: access-order LinkedHashMap mutates on read, so a
     * concurrent wrapper would not be safe here anyway, and the map is tiny.
     */
    private final LinkedHashMap<String, List<String>> backends =
            new LinkedHashMap<String, List<String>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > MAX_NETWORKS;
                }
            };

    private final transient PeerDirectory peerDirectory;

    public BackendMap(PeerDirectory peerDirectory) {
        this.peerDirectory = peerDirectory;
    }

    /**
     * Preferred backends for a network, in order. Empty if the network is not tracked.
     * Counts as an access for LRU purposes.
     */
    public List<String> get(String networkID) {
        if (networkID == null) return Collections.emptyList();
        synchronized (backends) {
            List<String> ids = backends.get(networkID);
            return ids == null ? Collections.emptyList() : new ArrayList<>(ids);
        }
    }

    public boolean isTracked(String networkID) {
        synchronized (backends) {
            // containsKey does not count as an access on an access-order map.
            return backends.containsKey(networkID);
        }
    }

    public int trackedNetworks() {
        synchronized (backends) {
            return backends.size();
        }
    }

    /**
     * Recompute the preferred backends for a network from its authoritative backendSet, ranked by
     * the priscore of each backend's directory entry.
     *
     * Pins whatever it selects so PeerDirectory cannot evict a backend this map still points at,
     * and unpins anything it drops. Without that, sections 1 and 2 of the design contradict each
     * other: the directory would be free to evict an entry the backend map depends on.
     */
    public List<String> refresh(CXNetwork network, String networkID, String ownID) {
        if (networkID == null) return Collections.emptyList();
        if (network == null || network.configuration == null
                || network.configuration.backendSet == null
                || network.configuration.backendSet.isEmpty()) {
            return get(networkID);
        }

        List<String> candidates = new ArrayList<>();
        for (String cxID : network.configuration.backendSet) {
            if (cxID == null) continue;
            if (ownID != null && ownID.equals(cxID)) continue;
            candidates.add(cxID);
        }
        if (candidates.isEmpty()) return get(networkID);

        // Highest priscore first. An unknown backend scores 0 and sorts last, but stays a
        // candidate: it may be the only route into a network this node has just joined.
        candidates.sort(Comparator.comparingDouble((String id) ->
                -(peerDirectory != null ? peerDirectory.priscore(id) : 0f)));

        List<String> chosen = new ArrayList<>(
                candidates.subList(0, Math.min(MAX_BACKENDS_PER_NETWORK, candidates.size())));

        List<String> previous;
        synchronized (backends) {
            previous = backends.put(networkID, chosen);
        }

        repin(previous, chosen);

        if (log.isDebugEnabled()) {
            log.debug("[BackendMap] {} -> {} priority backends of {} candidates",
                    networkID, chosen.size(), candidates.size());
        }
        return new ArrayList<>(chosen);
    }

    /** Stop tracking a network and release its pins. */
    public void remove(String networkID) {
        List<String> previous;
        synchronized (backends) {
            previous = backends.remove(networkID);
        }
        repin(previous, Collections.emptyList());
    }

    /**
     * Pin everything newly chosen, unpin anything dropped that no other tracked network still
     * holds. A backend shared by two networks must stay pinned when one of them drops it.
     */
    private void repin(List<String> previous, List<String> chosen) {
        if (peerDirectory == null) return;

        for (String cxID : chosen) {
            PeerEntry pe = peerDirectory.entry(cxID);
            if (pe != null) pe.backendPinned = true;
        }

        if (previous == null) return;
        Set<String> stillPinned = allPinned();
        for (String cxID : previous) {
            if (stillPinned.contains(cxID)) continue;
            PeerEntry pe = peerDirectory.entry(cxID);
            if (pe != null) pe.backendPinned = false;
        }
    }

    /** Every cxID currently held as a priority backend by any tracked network. */
    public Set<String> allPinned() {
        Set<String> out = new java.util.HashSet<>();
        synchronized (backends) {
            for (List<String> ids : backends.values()) {
                if (ids != null) out.addAll(ids);
            }
        }
        return out;
    }
}
