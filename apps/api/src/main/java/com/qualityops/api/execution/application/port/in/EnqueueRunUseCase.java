package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;

import java.util.UUID;

/** The single admission point: both POST /api/v1/runs (via RunService) and a
 *  fired Schedule (via ScheduleFireService) call this. Validates, freezes the
 *  snapshot, inserts test_runs PENDING + run_queue QUEUED with the frozen
 *  RunRequestedEvent — publishes NOTHING (the dispatcher does that later). */
public interface EnqueueRunUseCase {

    EnqueueRunResult enqueue(EnqueueRunCommand cmd);

    /** ADR-007 §2.3 — enqueue a fresh retry run that replays the original's frozen
     *  snapshot byte-identically (domain rule #2: no re-validation, no re-freeze).
     *  New {@code runId}/{@code executionId}/{@code eventId}, same
     *  {@code correlationId}. Runs in the caller's transaction; publishes nothing. */
    EnqueueRunResult enqueueRetry(UUID originalRunId, UUID orgId);

    record EnqueueRunCommand(
            UUID orgId, UUID projectId, UUID suiteId, UUID environmentId,
            UUID triggeredBy, RunPriority priority, RunSource source, UUID scheduleId /* nullable */) {}

    record EnqueueRunResult(UUID runId, UUID executionId, QueueState queueState) {}
}
