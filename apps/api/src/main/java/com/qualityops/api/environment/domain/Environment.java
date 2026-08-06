package com.qualityops.api.environment.domain;

import java.time.Instant;
import java.util.UUID;

public record Environment(
    UUID id,
    UUID orgId,
    UUID projectId,
    String name,
    String baseUrl,
    EnvironmentType type,
    EnvironmentStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {}
