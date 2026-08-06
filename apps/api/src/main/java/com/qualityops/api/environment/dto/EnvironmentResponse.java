package com.qualityops.api.environment.dto;

import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.domain.EnvironmentType;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentResponse(
    UUID id,
    UUID projectId,
    String name,
    String baseUrl,
    EnvironmentType type,
    EnvironmentStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static EnvironmentResponse from(Environment environment) {
        return new EnvironmentResponse(
            environment.id(),
            environment.projectId(),
            environment.name(),
            environment.baseUrl(),
            environment.type(),
            environment.status(),
            environment.createdAt(),
            environment.updatedAt()
        );
    }
}
