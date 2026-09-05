package com.qualityops.api.execution.dto;

import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;

import java.time.Instant;
import java.util.UUID;

public record RunResponse(
    UUID id,
    UUID projectId,
    UUID suiteId,
    UUID environmentId,
    RunStatus status,
    UUID triggeredBy,
    Instant startedAt,
    Instant completedAt,
    Instant createdAt,
    QueueState queueState,       // nullable — pre-2C runs
    RunPriority priority,        // nullable — pre-2C runs
    Boolean cancelRequested,     // nullable — pre-2C runs
    UUID retryOf,                // nullable — non-retry runs / pre-2D (ADR-007 §2.3)
    Integer retryCount,          // nullable — pre-2D
    RepositoryRunResponse repositoryRun  // nullable — non-repository runs (ADR-009 §11)
) {
    public static RunResponse from(TestRun run) {
        return from(run, null, null, null, null, null, null);
    }

    public static RunResponse from(TestRun run, QueueState queueState, RunPriority priority,
                                   Boolean cancelRequested) {
        return from(run, queueState, priority, cancelRequested, null, null, null);
    }

    public static RunResponse from(TestRun run, QueueState queueState, RunPriority priority,
                                   Boolean cancelRequested, UUID retryOf, Integer retryCount) {
        return from(run, queueState, priority, cancelRequested, retryOf, retryCount, null);
    }

    public static RunResponse from(TestRun run, QueueState queueState, RunPriority priority,
                                   Boolean cancelRequested, UUID retryOf, Integer retryCount,
                                   RepositoryRunResponse repositoryRun) {
        return new RunResponse(
            run.id(),
            run.projectId(),
            run.suiteId(),
            run.environmentId(),
            run.status(),
            run.triggeredBy(),
            run.startedAt(),
            run.completedAt(),
            run.createdAt(),
            queueState,
            priority,
            cancelRequested,
            retryOf,
            retryCount,
            repositoryRun
        );
    }
}
