package com.qualityops.api.environment.application.port.out;

import com.qualityops.api.environment.domain.EnvironmentHealthStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import com.qualityops.api.environment.dto.EnvironmentHealthCheckView;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-008 §3 — outbound-port for the environment-health read/write path. The API
 * stays the sole writer: {@link #recordProbe} does the {@code environments} UPDATE
 * and the {@code environment_health_check} INSERT in one transaction, guarded by
 * {@code org_id}. {@link #selectDueBatch} and {@link #countActiveByHealthStatus}
 * are unfiltered platform scans by design — read-only, and every write derived
 * from them is keyed on the row's own {@code org_id}.
 */
public interface EnvironmentHealthRepository {

    /** ACTIVE, non-deleted STAGING/PRODUCTION envs past their probe cadence, {@code FOR UPDATE SKIP LOCKED}. */
    List<Candidate> selectDueBatch(int batchSize, Duration probeInterval);

    /** Guarded {@code UPDATE environments ... WHERE id=? AND org_id=?} + history INSERT, one transaction. */
    void recordProbe(RecordProbeCommand cmd);

    /** Current health row + the last 20 probe results, or empty when the env is not in the org. */
    Optional<EnvironmentHealthView> getView(UUID envId, UUID orgId);

    /** {@code (health_status, consecutive_failures)} for one env in one org, or empty. */
    Optional<CurrentState> currentState(UUID envId, UUID orgId);

    /** Retention sweep for {@code environment_health_check}. Returns the deleted row count. */
    int deleteChecksOlderThan(Instant cutoff);

    /** Count of ACTIVE STAGING/PRODUCTION envs in each health state (feeds the gauge). */
    Map<EnvironmentHealthStatus, Long> countActiveByHealthStatus();

    record Candidate(UUID id, UUID orgId, UUID projectId, String baseUrl, EnvironmentType type) {}

    record RecordProbeCommand(
        UUID envId,
        UUID orgId,
        UUID projectId,
        EnvironmentHealthStatus status,
        Instant checkedAt,
        Integer httpStatus,
        Integer latencyMs,
        String errorDetail,
        int consecutiveFailures,
        Instant lastHealthyAt
    ) {}

    record CurrentState(EnvironmentHealthStatus status, int consecutiveFailures) {}

    record EnvironmentHealthView(
        EnvironmentHealthStatus healthStatus,
        Instant lastProbeAt,
        Instant lastHealthyAt,
        int consecutiveFailures,
        List<EnvironmentHealthCheckView> recentChecks
    ) {}
}
