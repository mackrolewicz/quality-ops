package com.qualityops.api.environment.dto;

import com.qualityops.api.environment.domain.EnvironmentHealthStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADR-008 §3 — {@code GET /api/v1/environments/{id}/health} body. */
public record EnvironmentHealthResponse(
    UUID environmentId,
    EnvironmentHealthStatus healthStatus,
    Instant lastProbeAt,
    Instant lastHealthyAt,
    int consecutiveFailures,
    List<EnvironmentHealthCheckView> recentChecks
) {}
