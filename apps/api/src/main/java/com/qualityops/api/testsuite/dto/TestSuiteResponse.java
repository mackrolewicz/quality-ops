package com.qualityops.api.testsuite.dto;

import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestSuite;

import java.time.Instant;
import java.util.UUID;

public record TestSuiteResponse(
    UUID id,
    UUID projectId,
    String name,
    String description,
    SuiteType type,
    Instant createdAt,
    Instant updatedAt
) {
    public static TestSuiteResponse from(TestSuite suite) {
        return new TestSuiteResponse(
            suite.id(),
            suite.projectId(),
            suite.name(),
            suite.description(),
            suite.type(),
            suite.createdAt(),
            suite.updatedAt()
        );
    }
}
