package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.worker.config.WorkerExecutionProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public final class TargetValidator {

    public sealed interface Result permits Allowed, Blocked {}

    public record Allowed(URI uri, List<InetAddress> resolved) implements Result {}

    public record Blocked(String safeReason) implements Result {}

    private final WorkerExecutionProperties.Ssrf ssrf;
    private final CidrBlockList cidr = new CidrBlockList();
    private final Set<String> allowedHostNames;

    public TargetValidator(WorkerExecutionProperties props) {
        this.ssrf = props.ssrf();
        this.allowedHostNames = ssrf.allowedHosts() == null ? Set.of()
            : ssrf.allowedHosts().stream().map(String::toLowerCase).collect(Collectors.toSet());
    }

    /** Stage 1 — scheme / userinfo / host shape / port. No network. */
    public Result validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return new Blocked("missing or blank target URL");
        }
        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            return new Blocked("malformed URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new Blocked("unsupported URL scheme");
        }
        if (uri.getUserInfo() != null) {
            return new Blocked("credentials in URL are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new Blocked("missing host");
        }

        int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
        var allowedPorts = ssrf.allowedPorts();
        if (allowedPorts != null && !allowedPorts.isEmpty() && !allowedPorts.contains(port)) {
            return new Blocked("port not allowed");
        }
        return new Allowed(uri, List.of());
    }

    /** Stage 2 — resolve, then reject if ANY resolved address is dangerous. */
    public Result validateResolved(String host, List<InetAddress> addresses) {
        boolean allowPrivate = ssrf.allowPrivateTargets()
            && allowedHostNames.contains(host.toLowerCase());

        for (InetAddress a : addresses) {
            if (a.isAnyLocalAddress() || a.isMulticastAddress()) {
                return new Blocked("blocked address range");
            }
            if (isMetadataEndpoint(a)) {
                return new Blocked("blocked address range");
            }
            if (allowPrivate) {
                continue;
            }
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
                return new Blocked("blocked address range");
            }
            if (cidr.isBlocked(a)) {
                return new Blocked("blocked address range");
            }
        }
        return new Allowed(URI.create("resolved://" + host), List.copyOf(addresses));
    }

    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }

    private static boolean isMetadataEndpoint(InetAddress a) {
        return "169.254.169.254".equals(a.getHostAddress());
    }
}
