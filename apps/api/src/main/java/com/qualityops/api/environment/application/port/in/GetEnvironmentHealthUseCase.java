package com.qualityops.api.environment.application.port.in;

import com.qualityops.api.environment.dto.EnvironmentHealthResponse;

import java.util.UUID;

/** ADR-008 §3 — {@code GET /api/v1/environments/{id}/health}. */
public interface GetEnvironmentHealthUseCase {

    EnvironmentHealthResponse getHealth(UUID environmentId, UUID orgId);
}
