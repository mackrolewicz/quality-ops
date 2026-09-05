package com.qualityops.api.scm.application.service;

import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.RepositoryRunRequest;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.ResolveRepositoryRunCommand;
import com.qualityops.api.scm.application.port.out.ScmPort;
import com.qualityops.api.scm.application.port.out.ScmPort.RepositoryTarget;
import com.qualityops.api.scm.application.port.out.ScmPort.ResolvedCommit;
import com.qualityops.api.scm.domain.RepositoryConnection;
import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.scm.exception.ScmCredentialUnresolvedException;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepositoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR-009 §4 — the enqueue-time preflight: SHA freeze + digest-pinned image
 *  freeze + {@code qualityops.repo.ref_resolve} outcome tagging. */
class RepositoryRunPreflightServiceTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PYTEST_IMAGE = "python:3.12-slim@sha256:"
        + "1111111111111111111111111111111111111111111111111111111111111111";

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();

    private ScmTargetResolver targetResolver;
    private ScmPort scmPort;
    private SimpleMeterRegistry registry;
    private RepositoryRunPreflightService service;

    @BeforeEach
    void setUp() {
        targetResolver = mock(ScmTargetResolver.class);
        scmPort = mock(ScmPort.class);
        registry = new SimpleMeterRegistry();
        service = new RepositoryRunPreflightService(targetResolver, props(), new ScmMetrics(registry));
    }

    private static RepoExecApiProperties props() {
        var scm = new RepoExecApiProperties.Scm(List.of("github.com"), false, Duration.ofSeconds(5),
            Duration.ofSeconds(10), "P_", "", "", "");
        return new RepoExecApiProperties(true, new RepoExecApiProperties.Images(
            "pw@sha256:x", "junit@sha256:x", PYTEST_IMAGE, "cy@sha256:x", "k6@sha256:x"),
            Duration.ofMinutes(10), Duration.ofMinutes(30), null, scm, false);
    }

    private RepositoryConnection connection() {
        var now = Instant.now();
        return new RepositoryConnection(connectionId, orgId, projectId, RepositoryProvider.GITHUB,
            "github.com", "acme", "web", "develop", "GH_PAT", UUID.randomUUID(), now, now, null);
    }

    private void stubResolver(RepositoryConnection connection) {
        var target = new RepositoryTarget(RepositoryProvider.GITHUB, "github.com", "acme", "web");
        when(targetResolver.resolve(connectionId, orgId, projectId))
            .thenReturn(new ScmTargetResolver.Resolved(connection, target, "ghp_token", scmPort));
    }

    private RepositoryRunRequest request(String ref, Integer timeout) {
        return new RepositoryRunRequest(connectionId, ref, "PYTEST", null,
            List.of("pytest", "--junitxml=report.xml"), "JUNIT_XML", List.of("report.xml"), List.of(),
            List.of(), List.of(), null, null, timeout);
    }

    private ResolvedRepositoryRunResolve resolve(RepositoryRunRequest request) {
        var result = service.resolve(new ResolveRepositoryRunCommand(orgId, projectId, request));
        return new ResolvedRepositoryRunResolve(result.snapshot(), result.stagedRow());
    }

    private record ResolvedRepositoryRunResolve(com.qualityops.events.RepoTestSnapshot snapshot,
        com.qualityops.api.scm.application.port.in.RepositoryRunFrozen frozen) {}

    @Test
    void resolve_resolvableRef_freezesShaAndDigestPinnedImage() {
        stubResolver(connection());
        when(scmPort.resolveRef(any(), eq("main"), any()))
            .thenReturn(new ResolvedCommit(SHA, RepoRefType.BRANCH, Instant.now(), "subject"));

        var out = resolve(request("main", null));

        assertThat(out.snapshot().commitSha()).isEqualTo(SHA);
        assertThat(out.snapshot().runnerImageRef()).isEqualTo(PYTEST_IMAGE);
        assertThat(out.snapshot().runnerImageRef()).contains("@sha256:");
        assertThat(out.frozen().commitSha()).isEqualTo(SHA);
        assertThat(out.frozen().runnerImageRef()).isEqualTo(PYTEST_IMAGE);
        assertThat(out.snapshot().credentialRef()).isEqualTo("GH_PAT");
        assertThat(registry.get("qualityops.repo.ref_resolve")
            .tag("provider", "GITHUB").tag("outcome", "resolved").timer().count()).isEqualTo(1);
    }

    @Test
    void resolve_blankRequestedRef_fallsBackToConnectionDefaultRef() {
        stubResolver(connection());
        when(scmPort.resolveRef(any(), eq("develop"), any()))
            .thenReturn(new ResolvedCommit(SHA, RepoRefType.BRANCH, Instant.now(), "s"));

        var out = resolve(request(null, null));

        assertThat(out.snapshot().requestedRef()).isEqualTo("develop");
        verify(scmPort).resolveRef(any(), eq("develop"), any());
    }

    @Test
    void resolve_timeoutBeyondMax_isClampedToMaxRunTimeout() {
        stubResolver(connection());
        when(scmPort.resolveRef(any(), any(), any()))
            .thenReturn(new ResolvedCommit(SHA, RepoRefType.BRANCH, Instant.now(), "s"));

        var out = resolve(request("main", 999_999));

        assertThat(out.snapshot().timeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void resolve_refUnresolvable_propagatesAndRecordsNotFoundOutcome() {
        stubResolver(connection());
        when(scmPort.resolveRef(any(), any(), any()))
            .thenThrow(new RepositoryRefUnresolvableException("no such ref"));

        assertThatThrownBy(() -> service.resolve(
                new ResolveRepositoryRunCommand(orgId, projectId, request("gone", null))))
            .isInstanceOf(RepositoryRefUnresolvableException.class);
        assertThat(registry.get("qualityops.repo.ref_resolve")
            .tag("outcome", "not_found").timer().count()).isEqualTo(1);
    }

    @Test
    void resolve_credentialUnresolved_propagatesFromTargetResolver() {
        when(targetResolver.resolve(connectionId, orgId, projectId))
            .thenThrow(new ScmCredentialUnresolvedException("GH_PAT"));

        assertThatThrownBy(() -> service.resolve(
                new ResolveRepositoryRunCommand(orgId, projectId, request("main", null))))
            .isInstanceOf(ScmCredentialUnresolvedException.class);
    }
}
