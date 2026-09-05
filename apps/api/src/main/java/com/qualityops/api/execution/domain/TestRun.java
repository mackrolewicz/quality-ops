package com.qualityops.api.execution.domain;

import java.time.Instant;
import java.util.UUID;

public record TestRun(
    UUID id,
    UUID orgId,
    UUID projectId,
    UUID suiteId,
    UUID environmentId,
    UUID executionId,
    RunStatus status,
    UUID triggeredBy,
    RunConfigSnapshot configSnapshot,
    Instant startedAt,
    Instant completedAt,
    Instant createdAt
) {}
