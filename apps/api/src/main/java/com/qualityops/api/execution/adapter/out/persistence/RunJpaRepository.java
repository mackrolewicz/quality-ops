package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RunJpaRepository extends JpaRepository<RunEntity, UUID> {

    Optional<RunEntity> findByIdAndOrgId(UUID id, UUID orgId);

    // The nullable enum params are wrapped in CAST(... AS string) on the null-guard
    // so Postgres can infer a concrete type for the bind parameter — a bare
    // `:status IS NULL` against a NAMED_ENUM column fails with
    // "could not determine data type of parameter". The equality branch still
    // compares the enum directly.
    @Query("SELECT r FROM RunEntity r WHERE r.orgId = :orgId " +
        "AND (:projectId IS NULL OR r.projectId = :projectId) " +
        "AND (:suiteId IS NULL OR r.suiteId = :suiteId) " +
        "AND (CAST(:status AS string) IS NULL OR r.status = :status) " +
        "AND (CAST(:queueState AS string) IS NULL OR EXISTS (SELECT 1 FROM RunQueueEntity q " +
        "     WHERE q.runId = r.id AND q.orgId = r.orgId AND q.queueState = :queueState))")
    Page<RunEntity> findAllByOrgId(@Param("orgId") UUID orgId,
                                    @Param("projectId") UUID projectId,
                                    @Param("suiteId") UUID suiteId,
                                    @Param("status") RunStatus status,
                                    @Param("queueState") QueueState queueState,
                                    Pageable pageable);

    // Drives PENDING -> {FAILED, CANCELLED} for a run that never started: a
    // QUEUED cancel, or a dispatch abandoned before any runs.requested. Guarded
    // on id + orgId + status='PENDING' so it is a silent no-op once the run has
    // left PENDING, and always filters by orgId (multi-tenancy rule).
    @Modifying
    @Query("UPDATE RunEntity r SET r.status = :to, r.completedAt = :ts, " +
        "r.startedAt = COALESCE(r.startedAt, :ts) " +
        "WHERE r.id = :runId AND r.orgId = :orgId AND r.status = :from")
    int markPendingTerminal(@Param("runId") UUID runId, @Param("orgId") UUID orgId,
                            @Param("from") RunStatus from, @Param("to") RunStatus to, @Param("ts") Instant ts);

    // Two separate conditional UPDATEs (rather than a single JPQL CASE
    // expression) to avoid brittle enum-vs-string comparisons in JPQL.
    // Each is atomic and keyed on id + orgId + fromStatus, satisfying the
    // idempotency contract in RunRepository#transitionStatus and the
    // multi-tenancy rule (every write filters by orgId).
    @Modifying
    @Query("UPDATE RunEntity r SET r.status = :toStatus, r.startedAt = :timestamp " +
        "WHERE r.id = :runId AND r.orgId = :orgId AND r.executionId = :executionId " +
        "AND r.status = :fromStatus")
    int markRunning(@Param("runId") UUID runId,
                     @Param("orgId") UUID orgId,
                     @Param("executionId") UUID executionId,
                     @Param("fromStatus") RunStatus fromStatus,
                     @Param("toStatus") RunStatus toStatus,
                     @Param("timestamp") Instant timestamp);

    @Modifying
    @Query("UPDATE RunEntity r SET r.status = :toStatus, r.completedAt = :timestamp " +
        "WHERE r.id = :runId AND r.orgId = :orgId AND r.executionId = :executionId " +
        "AND r.status = :fromStatus")
    int markResolved(@Param("runId") UUID runId,
                      @Param("orgId") UUID orgId,
                      @Param("executionId") UUID executionId,
                      @Param("fromStatus") RunStatus fromStatus,
                      @Param("toStatus") RunStatus toStatus,
                      @Param("timestamp") Instant timestamp);

    // Terminal transition tolerant of cross-topic reorder: accepts PENDING or
    // RUNNING as the source state, and COALESCEs started_at so a run that reached
    // terminal without a prior runs.started still has a non-null started_at.
    @Modifying
    @Query("UPDATE RunEntity r SET r.status = :toStatus, r.completedAt = :timestamp, " +
        "r.startedAt = COALESCE(r.startedAt, :timestamp) " +
        "WHERE r.id = :runId AND r.orgId = :orgId AND r.executionId = :executionId " +
        "AND r.status IN :fromStatuses")
    int markTerminal(@Param("runId") UUID runId,
                      @Param("orgId") UUID orgId,
                      @Param("executionId") UUID executionId,
                      @Param("toStatus") RunStatus toStatus,
                      @Param("timestamp") Instant timestamp,
                      @Param("fromStatuses") List<RunStatus> fromStatuses);

    // ADR-007 §1.3 — reaper terminal transition. NO executionId (the reaper is
    // driven by its own test_runs read). status IN :from is the whole guard;
    // always filters by orgId (multi-tenancy rule).
    @Modifying
    @Query("UPDATE RunEntity r SET r.status = :to, r.completedAt = :ts, " +
        "r.startedAt = COALESCE(r.startedAt, :ts) " +
        "WHERE r.id = :runId AND r.orgId = :orgId AND r.status IN :from")
    int markReapedFailed(@Param("runId") UUID runId, @Param("orgId") UUID orgId,
                         @Param("to") RunStatus to, @Param("ts") Instant ts,
                         @Param("from") List<RunStatus> from);

    @Query(value = "SELECT config_snapshot::text FROM test_runs WHERE id = :id AND org_id = :orgId",
        nativeQuery = true)
    Optional<String> findConfigSnapshotJson(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query(value = "SELECT COUNT(*) AS totalRuns, " +
        "COUNT(*) FILTER (WHERE status = 'PASSED') AS passedRuns, " +
        "COUNT(*) FILTER (WHERE status = 'FAILED') AS failedRuns " +
        "FROM test_runs WHERE org_id = :orgId AND project_id = :projectId AND created_at >= :since " +
        "AND status IN ('PASSED','FAILED')",
        nativeQuery = true)
    RunStatsProjection getStats(@Param("orgId") UUID orgId,
                                 @Param("projectId") UUID projectId,
                                 @Param("since") Instant since);
}
