package com.qualityops.api.environment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR-008 §3 — one row of {@code environment_health_check} (V20). {@code healthStatus}
 * is stored as a plain {@code String} (the column is {@code VARCHAR + CHECK}, not a
 * PostgreSQL named type) — deliberately no {@code @Enumerated}/{@code NAMED_ENUM}.
 */
@Entity
@Table(name = "environment_health_check")
class EnvironmentHealthCheckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "health_status", nullable = false)
    private String healthStatus;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EnvironmentHealthCheckEntity() {
    }

    EnvironmentHealthCheckEntity(UUID orgId, UUID environmentId, UUID projectId, Instant checkedAt,
                                String healthStatus, Integer httpStatus, Integer latencyMs,
                                String errorDetail) {
        this.orgId = orgId;
        this.environmentId = environmentId;
        this.projectId = projectId;
        this.checkedAt = checkedAt;
        this.healthStatus = healthStatus;
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.errorDetail = errorDetail;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    Instant getCheckedAt() {
        return checkedAt;
    }

    String getHealthStatus() {
        return healthStatus;
    }

    Integer getHttpStatus() {
        return httpStatus;
    }

    Integer getLatencyMs() {
        return latencyMs;
    }

    String getErrorDetail() {
        return errorDetail;
    }
}
