package com.qualityops.api.execution.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository {

    /** Insert-only — runs are immutable once created. */
    TestRun save(TestRun run);

    Optional<TestRun> findByIdAndOrgId(UUID id, UUID orgId);

    PageResult<TestRun> findAllByOrgId(UUID orgId, UUID projectIdFilter, UUID suiteIdFilter,
                                        RunStatus statusFilter, int page, int size);

    /**
     * Atomically transitions a run from {@code fromStatus} to {@code toStatus}.
     * Returns {@code true} if exactly one row was updated. Returns
     * {@code false} if the run wasn't in {@code fromStatus} — this is the
     * idempotency mechanism for at-least-once Kafka redelivery.
     */
    boolean transitionStatus(UUID runId, RunStatus fromStatus, RunStatus toStatus, Instant timestamp);

    RunStats getStats(UUID projectId, UUID orgId, Instant since);
}
