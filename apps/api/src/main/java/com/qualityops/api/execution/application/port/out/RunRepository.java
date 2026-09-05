package com.qualityops.api.execution.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.domain.QueueState;
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
                                        RunStatus statusFilter, QueueState queueStateFilter,
                                        int page, int size);

    /** PENDING -> CANCELLED for a run cancelled while still QUEUED. Sets
     *  completedAt and COALESCEs startedAt. Silent no-op if the run already left
     *  PENDING — the caller has already guarded via run_queue. */
    void transitionToCancelled(UUID runId, UUID orgId);

    /** PENDING -> FAILED for a run whose dispatch was abandoned before any
     *  runs.requested was published (corrupt frozen event, or send failed at the
     *  attempt ceiling). Legal lifecycle move — the run never started (domain
     *  rule #2). Silent no-op if the run already left PENDING. */
    void transitionToFailed(UUID runId, UUID orgId);

    /**
     * Atomically transitions a run (scoped to {@code orgId} and {@code executionId})
     * from {@code fromStatus} to {@code toStatus}. Returns {@code true} if exactly
     * one row was updated. Returns {@code false} if the run wasn't in
     * {@code fromStatus}, belongs to another tenant, or carries a stale/foreign
     * {@code executionId} — this is the idempotency mechanism for at-least-once
     * Kafka redelivery.
     */
    boolean transitionStatus(UUID runId, UUID orgId, UUID executionId,
                             RunStatus fromStatus, RunStatus toStatus, Instant timestamp);

    /**
     * Atomically moves a run (scoped to {@code orgId} and {@code executionId}) to
     * a terminal status from EITHER PENDING or RUNNING. Tolerates runs.completed /
     * runs.failed arriving before runs.started. Returns {@code true} iff exactly
     * one row changed; {@code false} (already terminal, foreign tenant, or a
     * stale/foreign {@code executionId}) is the idempotency signal under
     * at-least-once redelivery.
     */
    boolean transitionToTerminal(UUID runId, UUID orgId, UUID executionId,
                                 RunStatus terminalStatus, Instant timestamp);

    RunStats getStats(UUID projectId, UUID orgId, Instant since);

    /** ADR-007 §1.3 — PENDING|RUNNING -> FAILED for a run the reaper has judged
     *  stuck. Sets completed_at, COALESCEs started_at. NO executionId guard — the
     *  reaper is driven by its own test_runs read, not a Worker event. Silent
     *  no-op (0 rows) if a real terminal raced in. */
    int reapToFailed(UUID runId, UUID orgId, Instant ts);

    /** ADR-007 §2.3 — the original run's config_snapshot as a raw JSON string, so
     *  a retry can copy it byte-identically (domain rule #2 — no re-freeze). */
    Optional<String> findConfigSnapshotJson(UUID runId, UUID orgId);

    /** ADR-007 §2.3 — insert a fresh PENDING run for a retry, config_snapshot
     *  copied verbatim from the original (no deserialise/re-serialise). */
    TestRun saveRetryRun(RetryRunRow row);

    record RetryRunRow(UUID id, UUID orgId, UUID projectId, UUID suiteId, UUID environmentId,
                       UUID executionId, UUID triggeredBy, String configSnapshotJson, Instant createdAt) {}
}
