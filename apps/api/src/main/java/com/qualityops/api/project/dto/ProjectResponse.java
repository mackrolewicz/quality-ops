package com.qualityops.api.project.dto;

import com.qualityops.api.project.domain.Project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
    UUID id,
    String name,
    String description,
    String slug,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
            project.id(),
            project.name(),
            project.description(),
            project.slug(),
            project.createdBy(),
            project.createdAt(),
            project.updatedAt()
        );
    }
}
