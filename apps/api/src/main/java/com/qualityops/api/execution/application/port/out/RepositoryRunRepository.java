package com.qualityops.api.execution.application.port.out;

import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepositoryProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** ADR-009 §3 — persistence port for {@code repository_run} (1:1 with
 *  {@code test_runs}). Every method is org-scoped. */
public interface RepositoryRunRepository {

    /** Insert the frozen row (state PENDING). */
    void insertFrozen(UUID runId, UUID orgId, RepositoryRunFrozen frozen);

    /** Guarded state advance from {@code fromStates} → {@code toState}, additionally
     *  matched on {@code test_runs.execution_id}. @return rows affected. */
    int transitionState(UUID runId, UUID orgId, UUID executionId,
                        List<RepositoryRunState> fromStates, RepositoryRunState toState);

    /** Guarded telemetry apply (COALESCE timestamps); skipped for a CANCELLED row.
     *  @return rows affected. */
    int applyTelemetry(UUID runId, UUID orgId, UUID executionId, String imageDigest, Integer exitCode,
                       Integer itemsTotal, Integer itemsPassed, Integer itemsFailed, Integer itemsSkipped,
                       Instant checkoutAt, Instant startedAt, Instant finishedAt);

    Optional<RepositoryRunRow> findByRunIdAndOrgId(UUID runId, UUID orgId);

    Map<UUID, RepositoryRunRow> findByRunIdsAndOrgId(UUID orgId, List<UUID> runIds);

    record RepositoryRunRow(
        UUID runId, UUID orgId, RepositoryProvider provider, String repoHost, String repoPath,
        String requestedRef, String commitSha, RepoRefType refType, FrameworkPreset framework,
        String runnerImageRef, RepoResourceProfile resourceProfile, RepoNetworkPolicy networkPolicy,
        int timeoutSeconds, RepositoryRunState state, String runnerImageDigest, Integer containerExitCode,
        Integer itemsTotal, Integer itemsPassed, Integer itemsFailed, Integer itemsSkipped,
        Instant checkoutAt, Instant startedAt, Instant finishedAt, String errorDetail
    ) {}
}
