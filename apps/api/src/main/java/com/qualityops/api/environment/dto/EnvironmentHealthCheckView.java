package com.qualityops.api.environment.dto;

import com.qualityops.api.environment.domain.EnvironmentHealthStatus;

import java.time.Instant;

/** ADR-008 §3 — one historical probe result in the {@code /health} response. */
public record EnvironmentHealthCheckView(
    Instant checkedAt,
    EnvironmentHealthStatus healthStatus,
    Integer httpStatus,
    Integer latencyMs,
    String error
) {}
