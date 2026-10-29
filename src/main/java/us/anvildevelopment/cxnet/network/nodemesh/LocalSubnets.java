/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Whether an address sits on one of this machine's actual interface subnets.
 *
 * Deliberately not an RFC1918 range check. A range check would call a peer reachable over a VPN
 * "local", and would call CGNAT space (100.64.0.0/10) local as well, when a carrier-NAT peer is
 * neither cheap to reach nor on the same link. Matching real interface addresses and prefix
 * lengths is the only way to answer the question that actually matters: can this be reached
 * directly on a link I am attached to.
 *
 * Being local means cheap to reach, never trusted. A local peer still goes through the PGP nonce
 * challenge like any other.
 */
public final class LocalSubnets {
    private static final Logger log = LoggerFactory.getLogger(LocalSubnets.class);

    /** Carrier-grade NAT, 100.64.0.0/10. Never local, whatever an interface claims. */
    private static final long CGNAT_NET = ipToLong(new int[]{100, 64, 0, 0});
    private static final int CGNAT_PREFIX = 10;

    /**
     * Interface name fragments that indicate a tunnel rather than a link.
     *
     * A VPN's address is a genuine interface address, so enumeration alone would accept it. There
     * is no portable API that says "this interface is a tunnel", so this is a name heuristic
     * backed by the point-to-point check below. It errs toward excluding: a missed tunnel would
     * wrongly grant the +3 local tier and let a remote peer outrank a real LAN peer, whereas a
     * wrongly excluded interface only loses a bonus.
     */
    private static final String[] TUNNEL_HINTS = {
            "tun", "tap", "ppp", "vpn", "wg", "wireguard", "utun", "zt", "zerotier",
            "tailscale", "nordlynx", "proton", "openvpn", "ipsec", "gpd", "anyconnect",
            "teredo", "isatap", "6to4", "pan-gp", "softether", "hamachi", "radmin"
    };

    private static volatile List<Subnet> cached = null;
    private static volatile long cachedAt = 0L;

    /** Interface subnets are re-read at most this often. Links come and go. */
    private static final long CACHE_TTL_MS = 60_000L;

    private LocalSubnets() {}

    /** One interface subnet: network address plus prefix length. */
    private static final class Subnet {
        final long network;
        final int prefix;
        final String iface;

        Subnet(long network, int prefix, String iface) {
            this.network = network;
            this.prefix = prefix;
            this.iface = iface;
        }

        boolean contains(long addr) {
            if (prefix <= 0 || prefix > 32) return false;
            long mask = prefix == 32 ? 0xFFFFFFFFL : ~((1L << (32 - prefix)) - 1) & 0xFFFFFFFFL;
            return (addr & mask) == (network & mask);
        }
    }

    /**
     * Whether a host:port, bare host or IP literal is on a local interface subnet.
     * Returns false for anything that cannot be resolved without a lookup.
     */
    public static boolean isLocalAddress(String address) {
        if (address == null || address.isEmpty()) return false;
        String host = address;

        // Strip a :port, but leave IPv6 literals alone -- they are never local here since only
        // IPv4 interface subnets are matched.
        int lastColon = host.lastIndexOf(':');
        if (lastColon > 0 && host.indexOf(':') == lastColon) {
            host = host.substring(0, lastColon);
        }

        // A bridge address ("cxHTTP1:https://...") is not a link-local candidate.
        if (host.contains("/") || host.isEmpty()) return false;

        long addr = parseIPv4(host);
        if (addr < 0) return false;
        return isLocal(addr);
    }

    /** Whether an InetAddress is on a local interface subnet. */
    public static boolean isLocal(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        if (address.isLoopbackAddress()) return false;
        byte[] b = address.getAddress();
        long addr = ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16)
                  | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
        return isLocal(addr);
    }

    private static boolean isLocal(long addr) {
        // CGNAT is excluded up front. A carrier-NAT peer may well appear on an interface subnet,
        // but it is not on the same link and is not cheap to reach.
        if (inPrefix(addr, CGNAT_NET, CGNAT_PREFIX)) return false;

        for (Subnet s : subnets()) {
            if (s.contains(addr)) return true;
        }
        return false;
    }

    private static boolean inPrefix(long addr, long network, int prefix) {
        long mask = ~((1L << (32 - prefix)) - 1) & 0xFFFFFFFFL;
        return (addr & mask) == (network & mask);
    }

    /** Interface subnets, excluding loopback, tunnels and CGNAT-numbered links. */
    private static List<Subnet> subnets() {
        long now = System.currentTimeMillis();
        List<Subnet> snapshot = cached;
        if (snapshot != null && now - cachedAt < CACHE_TTL_MS) return snapshot;

        List<Subnet> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!usable(iface)) continue;

                for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;

                    int prefix = ia.getNetworkPrefixLength();
                    if (prefix <= 0 || prefix > 32) continue;

                    // A /32 describes one host, not a subnet, and is typical of a tunnel
                    // endpoint. It tells us nothing about who else is reachable on the link.
                    if (prefix == 32) continue;

                    byte[] b = a.getAddress();
                    long addr = ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16)
                              | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);

                    if (inPrefix(addr, CGNAT_NET, CGNAT_PREFIX)) continue;

                    found.add(new Subnet(addr, prefix, iface.getName()));
                }
            }
        } catch (Exception e) {
            log.debug("[LocalSubnets] Could not enumerate interfaces: {}", e.getMessage());
        }

        List<Subnet> result = Collections.unmodifiableList(found);
        cached = result;
        cachedAt = now;
        return result;
    }

    private static boolean usable(NetworkInterface iface) {
        try {
            if (iface.isLoopback() || !iface.isUp()) return false;
            // Point-to-point is a tunnel or dialup link, not a shared segment.
            if (iface.isPointToPoint()) return false;
            if (isTunnelName(iface.getName()) || isTunnelName(iface.getDisplayName())) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTunnelName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String hint : TUNNEL_HINTS) {
            if (n.contains(hint)) return true;
        }
        return false;
    }

    /** Parse a dotted-quad into a long, or -1 if it is not one. No DNS lookup. */
    private static long parseIPv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return -1;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return -1;
                octets[i] = v;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return ipToLong(octets);
    }

    private static long ipToLong(int[] o) {
        return ((long) o[0] << 24) | ((long) o[1] << 16) | ((long) o[2] << 8) | o[3];
    }

    /** Drop the cached interface list, so the next check re-reads. For tests and link changes. */
    public static void invalidate() {
        cached = null;
        cachedAt = 0L;
    }

    /** Human-readable description of the matched subnets, for diagnostics. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (Subnet s : subnets()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(longToIP(s.network)).append('/').append(s.prefix).append('@').append(s.iface);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String longToIP(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "."
             + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }

    /** Resolve a hostname without blocking indefinitely. Unused by the hot path. */
    public static boolean isLocalResolved(String host) {
        try {
            return isLocal(InetAddress.getByName(host));
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
