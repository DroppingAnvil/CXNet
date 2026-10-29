/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.anvildevelopment.util.tools.permissions.BasicPermissionContainer;
import us.anvildevelopment.util.tools.permissions.Entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hierarchical matching over the existing permission container.
 *
 * This is not a new permission system. {@link BasicPermissionContainer} already provides
 * everything else the design needs: {@code Entry.getAllow()} is a native explicit deny,
 * {@code allowed()} returns false on a miss so default-deny is already the behaviour, grants
 * already live in {@code CXNetwork.networkPermissions}, already sync in the seed, and are already
 * mutated by the GRANT_PERMISSION / REVOKE_PERMISSION handlers.
 *
 * What it lacks is matching. {@code allowed()} is an exact two-level map lookup:
 * {@code permissionSet.get(identity).get(permission).getAllow()}. A stored key
 * {@code CXNET.broadcast.*} therefore matches only a literal request for the string
 * {@code CXNET.broadcast.*} -- never {@code CXNET.broadcast.DIRECTORY_UPDATE}. There is no
 * wildcard expansion and no specificity ordering.
 *
 * So this class generates candidate keys most-specific-first and returns the first hit's verdict,
 * which gives most-specific-wins, wildcards, explicit-deny-beats-a-less-specific-allow and
 * default deny, while leaving storage, serialization and sync untouched.
 *
 * Generic by construction: nothing here knows about broadcast. {@code network.relay.*},
 * {@code network.directory.write} and plugin-namespaced nodes all resolve the same way.
 */
public final class PermissionResolver {
    private static final Logger log = LoggerFactory.getLogger(PermissionResolver.class);

    /** Pseudo-identity carrying a network's default policy. Already a convention in ConnectX. */
    public static final String DEFAULT_NODE = "DEFAULT_NODE";

    /**
     * Identity string that {@code BasicPermissionContainer.allowed()} short-circuits to true.
     *
     * The existing call sites guard it with {@code assert !deviceID.contains("SYSTEM")}, but
     * asserts are no-ops without {@code -ea} -- the same defect as the v0.6.2 NodeMesh:572
     * finding. Guarded here with a real runtime check.
     */
    private static final String SYSTEM = "SYSTEM";

    private PermissionResolver() {}

    /** The outcome of a resolution. Distinguishes "no rule matched" from "a rule said no". */
    public enum Verdict {
        ALLOW,
        DENY,
        /** No grant mentioned this node at any specificity. Treated as deny by callers. */
        UNSET;

        public boolean allowed() {
            return this == ALLOW;
        }
    }

    /**
     * Whether an identity holds a permission node. Default deny.
     *
     * @param container the network's permission container
     * @param identity  the identity to check, never a transmitter where authorship is the question
     * @param node      dotted permission node, e.g. {@code CXNET.broadcast.DIRECTORY_UPDATE}
     */
    public static boolean allowed(BasicPermissionContainer container, String identity, String node) {
        return resolve(container, identity, node).allowed();
    }

    /**
     * Resolve a permission node for an identity.
     *
     * Order: the identity's own grants most-specific-first, then DEFAULT_NODE most-specific-first.
     * A rule found on the identity wins outright, allow or deny, so a per-identity deny overrides
     * a permissive network default -- which is what makes revoking one misbehaving relay possible
     * on an otherwise open network.
     */
    public static Verdict resolve(BasicPermissionContainer container, String identity, String node) {
        if (container == null || container.permissionSet == null) return Verdict.UNSET;
        if (identity == null || node == null || node.isEmpty()) return Verdict.UNSET;

        // Never let a claimed "SYSTEM" identity reach allowed()'s bypass.
        if (SYSTEM.equals(identity) || identity.contains(SYSTEM)) {
            log.warn("[PermissionResolver] Refusing SYSTEM-like identity '{}' for node {}", identity, node);
            return Verdict.DENY;
        }

        Verdict own = resolveFor(container, identity, node);
        if (own != Verdict.UNSET) return own;

        if (!DEFAULT_NODE.equals(identity)) {
            return resolveFor(container, DEFAULT_NODE, node);
        }
        return Verdict.UNSET;
    }

    /** Resolve against one identity's grant map only. */
    private static Verdict resolveFor(BasicPermissionContainer container, String identity, String node) {
        Map<String, Entry> grants = container.permissionSet.get(identity);
        if (grants == null || grants.isEmpty()) return Verdict.UNSET;

        for (String candidate : candidates(node)) {
            Entry e = grants.get(candidate);
            if (e != null) {
                // A key holds exactly one Entry, so an explicit deny at the most specific
                // matching key simply wins -- no separate deny pass is needed.
                return e.getAllow() ? Verdict.ALLOW : Verdict.DENY;
            }
        }
        return Verdict.UNSET;
    }

    /**
     * Candidate keys for a node, most specific first.
     *
     * For {@code CXNET.broadcast.DIRECTORY_UPDATE}:
     * <pre>
     *   CXNET.broadcast.DIRECTORY_UPDATE
     *   CXNET.broadcast.*
     *   CXNET.*
     *   *
     * </pre>
     * A plugin node {@code net1.broadcast.myplugin.SYNC} yields
     * {@code ...myplugin.SYNC}, {@code ...myplugin.*}, {@code net1.broadcast.*}, {@code net1.*},
     * {@code *} -- so a grant on {@code net1.broadcast.*} covers a plugin's events, but a grant on
     * one plugin's namespace never leaks into another's.
     */
    public static List<String> candidates(String node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isEmpty()) return out;

        out.add(node);

        String[] segments = node.split("\\.");
        // Progressively replace the trailing segments with a wildcard.
        for (int keep = segments.length - 1; keep >= 1; keep--) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keep; i++) {
                sb.append(segments[i]).append('.');
            }
            sb.append('*');
            String candidate = sb.toString();
            if (!candidate.equals(node)) out.add(candidate);
        }
        out.add("*");
        return out;
    }

    /** Build a node string from parts, skipping nulls and empties. */
    public static String node(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('.');
            sb.append(p);
        }
        return sb.toString();
    }
}
