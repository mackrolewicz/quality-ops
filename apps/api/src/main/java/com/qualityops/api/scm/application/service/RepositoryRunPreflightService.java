package com.qualityops.api.scm.application.service;

import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase;
import com.qualityops.api.scm.application.port.out.ScmPort;
import com.qualityops.api.scm.application.port.out.ScmPort.ResolvedCommit;
import com.qualityops.api.scm.domain.RepositoryConnection;
import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.scm.exception.ScmAuthException;
import com.qualityops.events.EnvVar;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.SecretEnvVar;
import com.qualityops.events.SecretRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** ADR-009 §4 — the enqueue-time preflight: load + guard the connection, resolve
 *  the mutable ref to a 40-hex SHA at the provider, and freeze the immutable
 *  {@link RepoTestSnapshot} + {@link RepositoryRunFrozen} row (domain rule #2).
 *  No {@code @Transactional} — network I/O; the caller's {@code RunEnqueueService}
 *  transaction wraps the DB writes and rolls back on any throw here. */
@Service
public class RepositoryRunPreflightService implements ResolveRepositoryRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(RepositoryRunPreflightService.class);

    private final ScmTargetResolver targetResolver;
    private final RepoExecApiProperties props;
    private final ScmMetrics metrics;

    public RepositoryRunPreflightService(ScmTargetResolver targetResolver,
                                         RepoExecApiProperties props,
                                         ScmMetrics metrics) {
        this.targetResolver = targetResolver;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public ResolvedRepositoryRun resolve(ResolveRepositoryRunCommand command) {
        RepositoryRunRequest request = command.request();
        // Load + credential-resolve + host-allowlist + SSRF-guard + pick adapter.
        // A RepositoryHostNotAllowedException / ScmCredentialUnresolvedException here
        // surfaces as a 400 and rolls back the caller's enqueue transaction.
        ScmTargetResolver.Resolved resolved =
            targetResolver.resolve(request.connectionId(), command.orgId(), command.projectId());

        RepositoryConnection connection = resolved.connection();
        ScmPort port = resolved.port();
        FrameworkPreset framework = parse(FrameworkPreset.class, request.framework(), "framework");
        RepoReportFormat reportFormat = parse(RepoReportFormat.class, request.reportFormat(), "reportFormat");
        RepoResourceProfile resourceProfile = request.resourceProfile() == null
            || request.resourceProfile().isBlank()
            ? defaultResourceProfile()
            : parse(RepoResourceProfile.class, request.resourceProfile(), "resourceProfile");
        RepoNetworkPolicy networkPolicy = request.networkPolicy() == null || request.networkPolicy().isBlank()
            ? RepoNetworkPolicy.ISOLATED
            : parse(RepoNetworkPolicy.class, request.networkPolicy(), "networkPolicy");
        List<String> command2 = request.command();
        if (command2 == null || command2.isEmpty()) {
            throw new IllegalArgumentException("repository run command (argv) must not be empty");
        }
        int timeoutSeconds = clampTimeout(request.timeoutSeconds());
        String requestedRef = request.requestedRef() == null || request.requestedRef().isBlank()
            ? connection.defaultRef() : request.requestedRef().trim();

        long start = System.nanoTime();
        ResolvedCommit commit;
        try {
            commit = port.resolveRef(resolved.target(), requestedRef, resolved.credential());
        } catch (RepositoryRefUnresolvableException e) {
            metrics.refResolve(connection.provider(), "not_found", elapsed(start));
            throw e;
        } catch (ScmAuthException e) {
            metrics.refResolve(connection.provider(), "auth_failed", elapsed(start));
            throw e;
        } catch (RuntimeException e) {
            metrics.refResolve(connection.provider(), "error", elapsed(start));
            throw e;
        }
        metrics.refResolve(connection.provider(), "resolved", elapsed(start));

        String runnerImageRef = imageFor(framework);
        List<String> reportPaths = request.reportPaths() == null ? List.of() : List.copyOf(request.reportPaths());
        List<String> artifactGlobs =
            request.artifactGlobs() == null ? List.of() : List.copyOf(request.artifactGlobs());
        List<EnvVar> envVars = request.envVars() == null ? List.of()
            : request.envVars().stream().map(v -> new EnvVar(v.name(), v.value())).toList();
        List<SecretEnvVar> secretVars = request.secretRefs() == null ? List.of()
            : request.secretRefs().stream()
                .map(v -> new SecretEnvVar(v.name(), new SecretRef(v.secretRef())))
                .toList();

        var snapshot = new RepoTestSnapshot(
            connection.id(), connection.provider(), connection.host(), connection.repoPath(),
            requestedRef, commit.sha(), commit.refType(), framework, runnerImageRef,
            emptyToNull(request.workingDir()), List.copyOf(command2), reportFormat, reportPaths,
            artifactGlobs, envVars, secretVars, connection.credentialRef(), resourceProfile,
            networkPolicy, timeoutSeconds);

        var frozen = new RepositoryRunFrozen(
            connection.id(), connection.provider(), connection.host(), connection.repoPath(),
            requestedRef, commit.sha(), commit.refType(), framework, runnerImageRef,
            emptyToNull(request.workingDir()), List.copyOf(command2), reportFormat, reportPaths,
            artifactGlobs, resourceProfile, networkPolicy, timeoutSeconds);

        log.info("Froze repository run for connection {} ref {} -> {} ({})",
            connection.id(), requestedRef, commit.sha(), framework);
        return new ResolvedRepositoryRun(snapshot, frozen);
    }

    private String imageFor(FrameworkPreset framework) {
        RepoExecApiProperties.Images images = props.images();
        String ref = images == null ? null : images.forPreset(framework);
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("No allowlisted runner image configured for framework " + framework);
        }
        return ref;
    }

    private RepoResourceProfile defaultResourceProfile() {
        return props.defaultResourceProfile() == null ? RepoResourceProfile.SMALL : props.defaultResourceProfile();
    }

    private int clampTimeout(Integer requested) {
        long floor = 30;
        long ceiling = props.maxRunTimeout() == null ? 1800 : props.maxRunTimeout().toSeconds();
        long value = requested != null ? requested
            : (props.defaultRunTimeout() == null ? 600 : props.defaultRunTimeout().toSeconds());
        return (int) Math.max(floor, Math.min(value, ceiling));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
