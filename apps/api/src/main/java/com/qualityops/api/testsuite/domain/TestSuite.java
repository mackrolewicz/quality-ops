package com.qualityops.api.testsuite.domain;

import java.time.Instant;
import java.util.UUID;

public record TestSuite(
    UUID id,
    UUID orgId,
    UUID projectId,
    String name,
    String description,
    SuiteType type,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {}
