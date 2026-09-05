package com.qualityops.api.scm.application.service;

import com.qualityops.api.common.net.OutboundAddressGuard;
import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.application.port.out.RepositoryConnectionRepository;
import com.qualityops.api.scm.application.port.out.ScmCredentialResolver;
import com.qualityops.api.scm.application.port.out.ScmPort;
import com.qualityops.api.scm.application.port.out.ScmPort.RepositoryTarget;
import com.qualityops.api.scm.domain.RepositoryConnection;
import com.qualityops.api.scm.exception.RepositoryConnectionNotFoundException;
import com.qualityops.api.scm.exception.ScmCredentialUnresolvedException;
import com.qualityops.events.RepositoryProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** ADR-009 §4 — the shared "load connection → resolve credential → host
 *  allowlist + SSRF guard → pick provider adapter" pipeline used by both the
 *  "test connection" action and the enqueue-time preflight. No {@code @Transactional}
 *  (blocking DNS + outbound HTTP downstream). */
@Component
class ScmTargetResolver {

    private final RepositoryConnectionRepository connections;
    private final ScmCredentialResolver credentialResolver;
    private final ScmHostAllowlist hostAllowlist;
    private final OutboundAddressGuard addressGuard;
    private final RepoExecApiProperties props;
    private final List<ScmPort> scmPorts;

    ScmTargetResolver(RepositoryConnectionRepository connections,
                      ScmCredentialResolver credentialResolver,
                      ScmHostAllowlist hostAllowlist,
                      OutboundAddressGuard addressGuard,
                      RepoExecApiProperties props,
                      List<ScmPort> scmPorts) {
        this.connections = connections;
        this.credentialResolver = credentialResolver;
        this.hostAllowlist = hostAllowlist;
        this.addressGuard = addressGuard;
        this.props = props;
        this.scmPorts = scmPorts;
    }

    /** @param expectedProjectId when non-null, the connection's project must match
     *                           (else -> 404, an enqueue-time cross-project guard). */
    Resolved resolve(UUID connectionId, UUID orgId, UUID expectedProjectId) {
        RepositoryConnection connection = connections.findByIdAndOrgId(connectionId, orgId)
            .orElseThrow(() -> new RepositoryConnectionNotFoundException(connectionId));
        if (expectedProjectId != null && !expectedProjectId.equals(connection.projectId())) {
            throw new RepositoryConnectionNotFoundException(connectionId);
        }

        String credential = credentialResolver.resolve(connection.credentialRef());
        if (connection.credentialRef() != null && !connection.credentialRef().isBlank() && credential == null) {
            throw new ScmCredentialUnresolvedException(connection.credentialRef());
        }

        hostAllowlist.check(connection.host());
        boolean allowPrivate = props.scm() != null && props.scm().allowPrivateHosts();
        addressGuard.check("https://" + connection.host(), false, allowPrivate);

        ScmPort port = portFor(connection.provider());
        var target = new RepositoryTarget(connection.provider(), connection.host(),
            connection.ownerPath(), connection.repoName());
        return new Resolved(connection, target, credential, port);
    }

    private ScmPort portFor(RepositoryProvider provider) {
        return scmPorts.stream()
            .filter(p -> p.provider() == provider)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No SCM adapter for provider " + provider));
    }

    record Resolved(RepositoryConnection connection, RepositoryTarget target, String credential, ScmPort port) {}
}
