package com.qualityops.api.result.dto;

import com.qualityops.api.result.domain.ResultStatus;
import com.qualityops.api.result.domain.TestResult;

import java.time.Instant;
import java.util.UUID;

public record TestResultResponse(
    UUID id,
    UUID runId,
    UUID testCaseId,
    ResultStatus status,
    int durationMs,
    String errorMessage,
    int retryCount,
    Instant createdAt
) {
    public static TestResultResponse from(TestResult result) {
        return new TestResultResponse(
            result.id(),
            result.runId(),
            result.testCaseId(),
            result.status(),
            result.durationMs(),
            result.errorMessage(),
            result.retryCount(),
            result.createdAt()
        );
    }
}
