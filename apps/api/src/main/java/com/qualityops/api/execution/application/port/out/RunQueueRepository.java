package com.qualityops.api.execution.application.port.out;

import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RunQueueRepository {

    /** Insert a QUEUED row in the caller's transaction. No publish. */
    void enqueue(EnqueueRow row);

    /** ADR-007 §2.3 — insert a fresh QUEUED retry row (retry_of / retry_count set)
     *  in the caller's transaction. No publish. */
    void enqueueRetry(EnqueueRetryRow row);

    Optional<QueueRow> findByRunIdAndOrgId(UUID runId, UUID orgId);

    /** runId -> summary for a page of runs. */
    Map<UUID, QueueSummary> findSummariesByRunIds(UUID orgId, Collection<UUID> runIds);

    // dispatcher
    Map<UUID, Integer> countActivePerOrg();

    List<DispatchCandidate> selectQueuedCandidates(int batch, int agingStepSeconds, int agingMaxBoost);

    boolean claimForDispatch(UUID runId);

    /** DISPATCHED -> QUEUED (retry) or -> CANCELLED (if a cancel was requested in
     *  the send window). {@link RollbackOutcome#NOOP} means no DISPATCHED row
     *  matched. */
    RollbackOutcome rollbackDispatch(UUID runId);

    void markDispatchFailed(UUID runId);

    // reaper (ADR-007 §1)
    /** DISPATCHED + test_runs PENDING + grace < age < timeout, oldest first,
     *  FOR UPDATE SKIP LOCKED. */
    List<DispatchCandidate> selectStrandedDispatched(Instant graceCutoff, Instant timeoutCutoff, int batch);

    /** Re-claim a stranded row: advance dispatched_at + bump attempts. Guarded so a
     *  concurrent cancel or a real transition is not clobbered.
     *  @return true iff a DISPATCHED, not-cancel-requested, still-stale row moved. */
    boolean reclaimStranded(UUID runId, Instant graceCutoff);

    /** Atomic: a still-stale DISPATCHED row with {@code cancel_requested = true} ->
     *  CANCELLED (nulls {@code requested_event_json}). {@code true} iff a row moved. */
    boolean reclaimStrandedCancel(UUID runId, Instant graceCutoff);

    /** DISPATCHED/RUNNING queue rows whose run has shown no lifecycle progress past
     *  run-timeout. FOR UPDATE SKIP LOCKED. */
    List<StuckRun> selectStuckActive(Instant timeoutCutoff, int batch);

    // lifecycle
    boolean transitionQueueState(UUID runId, UUID orgId, Set<QueueState> from, QueueState to, boolean terminal);

    // cancel
    boolean cancelQueued(UUID runId, UUID orgId);

    /** @return {@code true} iff a DISPATCHED/RUNNING row for this tenant matched. */
    boolean requestCancel(UUID runId, UUID orgId);

    // maintenance + metrics
    int deleteTerminalOlderThan(Instant cutoff);

    Map<RunPriority, Long> queueDepthByPriority();

    Optional<Instant> oldestQueuedEnqueuedAt();

    long activeRunCount();

    // ADR-007 §2.3 / §3 — org-scoped aggregates
    long countRecentRetriesForOrg(UUID orgId, Instant since);

    Map<RunPriority, Long> queueDepthByPriorityForOrg(UUID orgId);

    Optional<Instant> oldestQueuedEnqueuedAtForOrg(UUID orgId);

    long activeRunCountForOrg(UUID orgId);

    record EnqueueRow(UUID orgId, UUID runId, UUID scheduleId, RunPriority priority,
                      String requestedEventJson, Instant enqueuedAt) {}

    record EnqueueRetryRow(UUID orgId, UUID runId, UUID scheduleId, RunPriority priority,
                           String requestedEventJson, Instant enqueuedAt, UUID retryOf, int retryCount) {}

    record QueueRow(UUID runId, UUID orgId, UUID scheduleId, RunPriority priority,
                    QueueState queueState, boolean cancelRequested, String requestedEventJson,
                    UUID retryOf, int retryCount) {}

    record QueueSummary(QueueState queueState, RunPriority priority, boolean cancelRequested,
                        UUID retryOf, int retryCount) {}

    record DispatchCandidate(UUID runId, UUID orgId, RunPriority priority, Instant enqueuedAt,
                             int dispatchAttempts, String requestedEventJson) {}

    record StuckRun(UUID runId, UUID orgId, QueueState queueState) {}

    enum RollbackOutcome { REQUEUED, CANCELLED, NOOP }
}
