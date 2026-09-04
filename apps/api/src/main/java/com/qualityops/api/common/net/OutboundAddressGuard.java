package com.qualityops.api.common.net;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * ADR-008 §3 — shared https(+http)-only + private/link-local/loopback denylist for
 * outbound HTTP from the API (webhook delivery, environment-health probes). A
 * lighter inline check than the Worker's {@code TargetValidator} (not on the API
 * classpath): resolves the host once, no redirect handling.
 *
 * <p>{@code allowPrivate} relaxes <em>only</em> RFC1918 site-local, CGNAT
 * (100.64.0.0/10), IPv6 ULA (fc00::/7) and 0.0.0.0/8. Loopback, link-local
 * (169.254.0.0/16 incl. the 169.254.169.254 cloud-metadata endpoint), any-local,
 * multicast and 255.255.255.255 are denied unconditionally.
 *
 * <p>Throws {@link IllegalArgumentException} on any violation (-> 400
 * {@code VALIDATION_ERROR}).
 */
@Component
public class OutboundAddressGuard {

    public void check(String url, boolean allowHttp, boolean allowPrivate) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
        String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase();
        boolean schemeOk = "https".equals(scheme) || (allowHttp && "http".equals(scheme));
        if (!schemeOk) {
            throw new IllegalArgumentException(
                allowHttp ? "url must be http or https" : "url must be https");
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url has no host");
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("url host does not resolve");
        }
        for (InetAddress a : addrs) {
            if (isDisallowed(a, allowPrivate)) {
                throw new IllegalArgumentException("url host resolves to a disallowed address");
            }
        }
    }

    private static boolean isDisallowed(InetAddress a, boolean allowPrivate) {
        boolean alwaysDenied = a.isLoopbackAddress() || a.isLinkLocalAddress()
            || a.isAnyLocalAddress() || a.isMulticastAddress()
            || is169254(a) || isBroadcastV4(a);
        if (alwaysDenied) {
            return true;
        }
        if (allowPrivate) {
            return false;
        }
        return a.isSiteLocalAddress() || isCgnat(a) || isUniqueLocalV6(a) || isThisNetworkV4(a);
    }

    /** IPv4 0.0.0.0/8 ("this network"). */
    private static boolean isThisNetworkV4(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 0;
    }

    /** IPv4 255.255.255.255 (limited broadcast). */
    private static boolean isBroadcastV4(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & b[1] & b[2] & b[3]) == (byte) 0xFF;
    }

    /** IPv4 100.64.0.0/10 (CGNAT). */
    private static boolean isCgnat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
    }

    /** IPv6 fc00::/7 (unique local). */
    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    /** IPv4 169.254.0.0/16 (belt-and-braces alongside isLinkLocalAddress). */
    private static boolean is169254(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 169 && (b[1] & 0xFF) == 254;
    }
}
