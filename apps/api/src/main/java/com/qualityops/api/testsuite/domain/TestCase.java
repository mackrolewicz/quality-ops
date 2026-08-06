package com.qualityops.api.testsuite.domain;

import java.time.Instant;
import java.util.UUID;

public record TestCase(
    UUID id,
    UUID orgId,
    UUID suiteId,
    String name,
    String description,
    int orderIndex,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {}
