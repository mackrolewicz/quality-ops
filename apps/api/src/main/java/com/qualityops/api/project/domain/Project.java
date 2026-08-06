package com.qualityops.api.project.domain;

import java.time.Instant;
import java.util.UUID;

public record Project(
    UUID id,
    UUID orgId,
    String name,
    String description,
    String slug,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {}
