package com.qualityops.api.scm.application.service;

import com.qualityops.api.audit.annotation.Audited;
import com.qualityops.api.audit.domain.AuditAction;
import com.qualityops.api.common.net.OutboundAddressGuard;
import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.scm.application.port.in.ManageRepositoryConnectionsUseCase;
import com.qualityops.api.scm.application.port.in.TestRepositoryConnectionUseCase;
import com.qualityops.api.scm.application.port.out.RepositoryConnectionRepository;
import com.qualityops.api.scm.application.port.out.ScmPort.ScmProbeResult;
import com.qualityops.api.scm.domain.RepositoryConnection;
import com.qualityops.api.scm.dto.RegisterRepositoryConnectionRequest;
import com.qualityops.api.scm.dto.RepositoryConnectionResponse;
import com.qualityops.api.scm.dto.TestConnectionResponse;
import com.qualityops.api.scm.dto.UpdateRepositoryConnectionRequest;
import com.qualityops.api.scm.exception.RepositoryConnectionInUseException;
import com.qualityops.api.scm.exception.RepositoryConnectionNotFoundException;
import com.qualityops.api.testsuite.application.port.in.CountTestCasesReferencingConnectionUseCase;
import com.qualityops.events.RepositoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADR-009 §11. Tenant-scoped CRUD + the outbound "test connection" probe. NOT
 *  class-{@code @Transactional}: {@code register}/{@code update} run a blocking
 *  DNS check in {@link OutboundAddressGuard} and {@code test} does outbound HTTP,
 *  neither of which may hold a DB transaction open (mirrors
 *  {@code WebhookEndpointService}). Never echoes a token — only the opaque
 *  {@code credentialRef}. */
@Service
public class RepositoryConnectionService
    implements ManageRepositoryConnectionsUseCase, TestRepositoryConnectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(RepositoryConnectionService.class);

    private final RepositoryConnectionRepository repo;
    private final GetProjectUseCase getProjectUseCase;
    private final ScmHostAllowlist hostAllowlist;
    private final OutboundAddressGuard addressGuard;
    private final RepoExecApiProperties props;
    private final CountTestCasesReferencingConnectionUseCase referenceCounter;
    private final ScmTargetResolver targetResolver;
    private final ScmMetrics metrics;

    public RepositoryConnectionService(RepositoryConnectionRepository repo,
                                       GetProjectUseCase getProjectUseCase,
                                       ScmHostAllowlist hostAllowlist,
                                       OutboundAddressGuard addressGuard,
                                       RepoExecApiProperties props,
                                       CountTestCasesReferencingConnectionUseCase referenceCounter,
                                       ScmTargetResolver targetResolver,
                                       ScmMetrics metrics) {
        this.repo = repo;
        this.getProjectUseCase = getProjectUseCase;
        this.hostAllowlist = hostAllowlist;
        this.addressGuard = addressGuard;
        this.props = props;
        this.referenceCounter = referenceCounter;
        this.targetResolver = targetResolver;
        this.metrics = metrics;
    }

    @Override
    @Audited(action = AuditAction.SCM_CONNECTION_CREATE, targetType = "repository_connection")
    public RepositoryConnectionResponse register(UUID projectId, UUID orgId,
                                                 RegisterRepositoryConnectionRequest request, UUID userId) {
        getProjectUseCase.getDomain(projectId, orgId); // ownership -> 404
        String host = resolveHost(request.provider(), request.host());
        validateHost(host);
        var now = Instant.now();
        var connection = new RepositoryConnection(UUID.randomUUID(), orgId, projectId, request.provider(),
            host, request.ownerPath().trim(), request.repoName().trim(),
            blankToDefault(request.defaultRef(), "main"), request.credentialRef(), userId, now, now, null);
        var saved = repo.create(connection);
        log.info("Registered repository connection {} for project {} in org {}", saved.id(), projectId, orgId);
        return RepositoryConnectionResponse.from(saved);
    }

    @Override
    public List<RepositoryConnectionResponse> list(UUID projectId, UUID orgId) {
        getProjectUseCase.getDomain(projectId, orgId); // ownership -> 404
        return repo.listForProject(orgId, projectId).stream()
            .map(RepositoryConnectionResponse::from)
            .toList();
    }

    @Override
    public RepositoryConnectionResponse get(UUID id, UUID orgId) {
        return RepositoryConnectionResponse.from(load(id, orgId));
    }

    @Override
    @Audited(action = AuditAction.SCM_CONNECTION_UPDATE, targetType = "repository_connection")
    public RepositoryConnectionResponse update(UUID id, UUID orgId, UpdateRepositoryConnectionRequest request) {
        var existing = load(id, orgId);
        String host = resolveHost(existing.provider(), request.host());
        validateHost(host);
        var updated = new RepositoryConnection(existing.id(), existing.orgId(), existing.projectId(),
            existing.provider(), host, request.ownerPath().trim(), request.repoName().trim(),
            blankToDefault(request.defaultRef(), "main"), request.credentialRef(), existing.createdBy(),
            existing.createdAt(), Instant.now(), null);
        return RepositoryConnectionResponse.from(repo.update(updated));
    }

    @Override
    @Audited(action = AuditAction.SCM_CONNECTION_DELETE, targetType = "repository_connection")
    public void delete(UUID id, UUID orgId) {
        load(id, orgId); // 404 if missing / foreign
        long referencing = referenceCounter.countReferencingConnection(id, orgId);
        if (referencing > 0) {
            throw new RepositoryConnectionInUseException(id, referencing);
        }
        repo.softDelete(id, orgId, Instant.now());
        log.info("Soft-deleted repository connection {} in org {}", id, orgId);
    }

    @Override
    @Audited(action = AuditAction.SCM_CONNECTION_TEST, targetType = "repository_connection")
    public TestConnectionResponse test(UUID id, UUID orgId) {
        var resolved = targetResolver.resolve(id, orgId, null);
        ScmProbeResult probe = resolved.port().probe(resolved.target(), resolved.credential());
        metrics.refResolve(resolved.connection().provider(), probe.ok() ? "resolved" : "error",
            Duration.ofMillis(Math.max(0, probe.latencyMs())));
        return new TestConnectionResponse(probe.ok(), probe.defaultBranch(), probe.resolvedHost(),
            probe.latencyMs(), probe.error());
    }

    private RepositoryConnection load(UUID id, UUID orgId) {
        return repo.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new RepositoryConnectionNotFoundException(id));
    }

    private void validateHost(String host) {
        hostAllowlist.check(host);
        boolean allowPrivate = props.scm() != null && props.scm().allowPrivateHosts();
        addressGuard.check("https://" + host, false, allowPrivate);
    }

    private static String resolveHost(RepositoryProvider provider, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return provider == RepositoryProvider.GITLAB ? "gitlab.com" : "github.com";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
