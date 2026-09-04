package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.domain.QueueState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RunQueueJpaRepository extends JpaRepository<RunQueueEntity, UUID> {

    Optional<RunQueueEntity> findByRunIdAndOrgId(UUID runId, UUID orgId);

    List<RunQueueEntity> findByRunIdInAndOrgId(Collection<UUID> runIds, UUID orgId);

    // ---- dispatcher: native, PG-specific (FOR UPDATE SKIP LOCKED + aging expr) ----
    @Query(value = """
        SELECT run_id, org_id, priority, enqueued_at, dispatch_attempts, requested_event_json
        FROM run_queue
        WHERE queue_state = 'QUEUED'
        ORDER BY (
            CASE priority WHEN 'HIGH' THEN 20 WHEN 'NORMAL' THEN 10 ELSE 0 END
          + LEAST(:agingMaxBoost,
                  FLOOR(EXTRACT(EPOCH FROM (now() - enqueued_at)) / :agingStepSeconds))
        ) DESC, enqueued_at ASC
        LIMIT :batch
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Object[]> selectQueuedCandidates(@Param("batch") int batch,
                                          @Param("agingStepSeconds") int agingStepSeconds,
                                          @Param("agingMaxBoost") int agingMaxBoost);

    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET queue_state = 'DISPATCHED', dispatched_at = now(),
            dispatch_attempts = dispatch_attempts + 1, last_dispatch_at = now()
        WHERE run_id = :runId AND queue_state = 'QUEUED'
        """, nativeQuery = true)
    int claimForDispatch(@Param("runId") UUID runId);

    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET queue_state = CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'QUEUED' END,
            dispatched_at = CASE WHEN cancel_requested THEN dispatched_at ELSE NULL END,
            terminal_at   = CASE WHEN cancel_requested THEN now() ELSE terminal_at END,
            requested_event_json = CASE WHEN cancel_requested THEN NULL ELSE requested_event_json END
        WHERE run_id = :runId AND queue_state = 'DISPATCHED'
        """, nativeQuery = true)
    int rollbackDispatch(@Param("runId") UUID runId);

    @Query("SELECT q.queueState FROM RunQueueEntity q WHERE q.runId = :runId")
    Optional<QueueState> findQueueStateByRunId(@Param("runId") UUID runId);

    // ---- reaper: stranded DISPATCHED (grace < age < timeout) ----
    // Deviation from ADR-007 §1.2's literal SQL: cancel_requested rows are NOT
    // excluded here. A stranded (never-published) DISPATCHED run can never be
    // reached by the cooperative runs.cancel path (the Worker never got the
    // request), so the reaper is the only thing that can resolve it — and when a
    // cancel is pending, CANCELLED is the right terminal. reconcileStranded picks
    // the branch via the two grace-guarded reclaim UPDATEs (reclaimStranded has
    // cancel_requested = FALSE; reclaimStrandedCancel has cancel_requested = TRUE).
    @Query(value = """
        SELECT rq.run_id, rq.org_id, rq.priority, rq.enqueued_at, rq.dispatch_attempts, rq.requested_event_json
        FROM run_queue rq JOIN test_runs tr ON tr.id = rq.run_id
        WHERE rq.queue_state = 'DISPATCHED' AND tr.status = 'PENDING'
          AND rq.dispatched_at <  :graceCutoff AND rq.dispatched_at >= :timeoutCutoff
        ORDER BY rq.dispatched_at
        LIMIT :batch
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Object[]> selectStrandedDispatched(@Param("graceCutoff") Instant graceCutoff,
                                            @Param("timeoutCutoff") Instant timeoutCutoff,
                                            @Param("batch") int batch);

    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET dispatched_at = now(), dispatch_attempts = dispatch_attempts + 1, last_dispatch_at = now()
        WHERE run_id = :runId AND queue_state = 'DISPATCHED'
          AND cancel_requested = FALSE AND dispatched_at < :graceCutoff
        """, nativeQuery = true)
    int reclaimStranded(@Param("runId") UUID runId, @Param("graceCutoff") Instant graceCutoff);

    /** Atomic counterpart of {@link #reclaimStranded} for the cancel-raced case: a
     *  still-stale DISPATCHED row whose cancel was requested in the dispatch window
     *  goes straight to CANCELLED. Guarded on {@code dispatched_at < :graceCutoff}
     *  so a legitimate concurrent re-dispatch (fresh {@code dispatched_at}) is left
     *  alone. */
    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET queue_state = 'CANCELLED', terminal_at = now(), requested_event_json = NULL
        WHERE run_id = :runId AND queue_state = 'DISPATCHED'
          AND cancel_requested = TRUE AND dispatched_at < :graceCutoff
        """, nativeQuery = true)
    int reclaimStrandedCancel(@Param("runId") UUID runId, @Param("graceCutoff") Instant graceCutoff);

    // ---- reaper: stuck active run past run-timeout ----
    @Query(value = """
        SELECT rq.run_id, rq.org_id, rq.queue_state
        FROM run_queue rq JOIN test_runs tr ON tr.id = rq.run_id
        WHERE rq.queue_state IN ('DISPATCHED', 'RUNNING')
          AND ( (tr.status = 'RUNNING' AND tr.started_at   < :timeoutCutoff)
             OR (tr.status = 'PENDING' AND rq.dispatched_at < :timeoutCutoff) )
        ORDER BY rq.dispatched_at
        LIMIT :batch
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Object[]> selectStuckActive(@Param("timeoutCutoff") Instant timeoutCutoff,
                                     @Param("batch") int batch);

    // ---- retry (ADR-007 §2.3) ----
    @Query("SELECT COUNT(q) FROM RunQueueEntity q "
        + "WHERE q.orgId = :orgId AND q.retryOf IS NOT NULL AND q.createdAt > :since")
    long countRecentRetriesForOrg(@Param("orgId") UUID orgId, @Param("since") Instant since);

    // ---- admin summary (ADR-007 §3), org-scoped ----
    @Query("SELECT q.priority, COUNT(q) FROM RunQueueEntity q "
        + "WHERE q.queueState = :state AND q.orgId = :orgId GROUP BY q.priority")
    List<Object[]> queueDepthByStateForOrg(@Param("state") QueueState state, @Param("orgId") UUID orgId);

    @Query("SELECT MIN(q.enqueuedAt) FROM RunQueueEntity q "
        + "WHERE q.queueState = :state AND q.orgId = :orgId")
    Instant oldestEnqueuedAtForOrg(@Param("state") QueueState state, @Param("orgId") UUID orgId);

    @Query("SELECT COUNT(q) FROM RunQueueEntity q "
        + "WHERE q.queueState IN :states AND q.orgId = :orgId")
    long countByStatesForOrg(@Param("states") Collection<QueueState> states, @Param("orgId") UUID orgId);

    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET queue_state = 'FAILED', terminal_at = now(), requested_event_json = NULL
        WHERE run_id = :runId AND queue_state = 'DISPATCHED'
        """, nativeQuery = true)
    int markDispatchFailed(@Param("runId") UUID runId);

    // ---- lifecycle queue transitions (gated by RunLifecycleService on the test_runs row-count) ----
    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET queue_state = :toState,
            terminal_at = CASE WHEN :terminal THEN now() ELSE terminal_at END,
            requested_event_json = CASE WHEN :terminal THEN NULL ELSE requested_event_json END
        WHERE run_id = :runId AND org_id = :orgId AND queue_state IN (:fromStates)
        """, nativeQuery = true)
    int transitionQueueState(@Param("runId") UUID runId, @Param("orgId") UUID orgId,
                             @Param("fromStates") Collection<String> fromStates,
                             @Param("toState") String toState, @Param("terminal") boolean terminal);

    // ---- cancel ----
    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET queue_state = 'CANCELLED', cancel_requested = TRUE,
            cancel_requested_at = now(), terminal_at = now(), requested_event_json = NULL
        WHERE run_id = :runId AND org_id = :orgId AND queue_state = 'QUEUED'
        """, nativeQuery = true)
    int cancelQueued(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    @Modifying
    @Query(value = """
        UPDATE run_queue
        SET cancel_requested = TRUE, cancel_requested_at = now()
        WHERE run_id = :runId AND org_id = :orgId AND queue_state IN ('DISPATCHED', 'RUNNING')
        """, nativeQuery = true)
    int requestCancel(@Param("runId") UUID runId, @Param("orgId") UUID orgId);

    // ---- maintenance + metrics (JPQL — clean type mapping via the entity) ----
    @Modifying
    @Query(value = """
        DELETE FROM run_queue
        WHERE queue_state IN ('COMPLETED', 'FAILED', 'CANCELLED') AND terminal_at < :cutoff
        """, nativeQuery = true)
    int deleteTerminalOlderThan(@Param("cutoff") Instant cutoff);

    @Query("SELECT q.orgId, COUNT(q) FROM RunQueueEntity q WHERE q.queueState IN :states GROUP BY q.orgId")
    List<Object[]> countActivePerOrg(@Param("states") Collection<QueueState> states);

    @Query("SELECT q.priority, COUNT(q) FROM RunQueueEntity q "
        + "WHERE q.queueState = :state GROUP BY q.priority")
    List<Object[]> queueDepthByState(@Param("state") QueueState state);

    @Query("SELECT MIN(q.enqueuedAt) FROM RunQueueEntity q WHERE q.queueState = :state")
    Instant oldestEnqueuedAt(@Param("state") QueueState state);

    @Query("SELECT COUNT(q) FROM RunQueueEntity q WHERE q.queueState IN :states")
    long countByStates(@Param("states") Collection<QueueState> states);
}
