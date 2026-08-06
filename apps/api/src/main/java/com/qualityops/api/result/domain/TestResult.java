package com.qualityops.api.result.domain;

import java.time.Instant;
import java.util.UUID;

public record TestResult(
    UUID id,
    UUID orgId,
    UUID runId,
    UUID testCaseId,
    ResultStatus status,
    int durationMs,
    String errorMessage,
    int retryCount,
    Instant createdAt
) {}
