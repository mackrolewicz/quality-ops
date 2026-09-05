package com.qualityops.api.environment.domain;

/**
 * ADR-008 §3 — operational health of a STAGING/PRODUCTION environment, derived from
 * periodic outbound probes. Plain Java enum: the backing column is
 * {@code environments.health_status VARCHAR(16) + CHECK} (V20), NOT a PostgreSQL
 * named type (the {@code environment_status} enum stays the admin-lifecycle flag).
 */
public enum EnvironmentHealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    DOWN
}
