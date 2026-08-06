package com.qualityops.api.execution.dto;

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
    Instant createdAt
) {
    public static RunResponse from(TestRun run) {
        return new RunResponse(
            run.id(),
            run.projectId(),
            run.suiteId(),
            run.environmentId(),
            run.status(),
            run.triggeredBy(),
            run.startedAt(),
            run.completedAt(),
            run.createdAt()
        );
    }
}
