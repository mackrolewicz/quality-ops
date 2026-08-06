package com.qualityops.api.testsuite.dto;

import com.qualityops.api.testsuite.domain.TestCase;

import java.time.Instant;
import java.util.UUID;

public record TestCaseResponse(
    UUID id,
    UUID suiteId,
    String name,
    String description,
    int orderIndex,
    Instant createdAt,
    Instant updatedAt
) {
    public static TestCaseResponse from(TestCase testCase) {
        return new TestCaseResponse(
            testCase.id(),
            testCase.suiteId(),
            testCase.name(),
            testCase.description(),
            testCase.orderIndex(),
            testCase.createdAt(),
            testCase.updatedAt()
        );
    }
}
