package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.domain.RunStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_runs")
class RunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    // @JdbcTypeCode(NAMED_ENUM) tells Hibernate 6 this is a PostgreSQL named enum,
    // preventing a type-mismatch during ddl-auto:validate.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "triggered_by", nullable = false)
    private UUID triggeredBy;

    // Kept as a pre-serialized JSON string; Jackson (de)serialization happens
    // in RunRepositoryAdapter, not here, to keep this entity a dumb holder.
    @Column(name = "config_snapshot", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String configSnapshotJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RunEntity() {}

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getSuiteId() {
        return suiteId;
    }

    UUID getEnvironmentId() {
        return environmentId;
    }

    RunStatus getStatus() {
        return status;
    }

    UUID getTriggeredBy() {
        return triggeredBy;
    }

    String getConfigSnapshotJson() {
        return configSnapshotJson;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    static RunEntity create(UUID id, UUID orgId, UUID projectId, UUID suiteId, UUID environmentId,
                             RunStatus status, UUID triggeredBy, String configSnapshotJson,
                             Instant startedAt, Instant completedAt, Instant createdAt) {
        var entity = new RunEntity();
        entity.id = id;
        entity.orgId = orgId;
        entity.projectId = projectId;
        entity.suiteId = suiteId;
        entity.environmentId = environmentId;
        entity.status = status;
        entity.triggeredBy = triggeredBy;
        entity.configSnapshotJson = configSnapshotJson;
        entity.startedAt = startedAt;
        entity.completedAt = completedAt;
        entity.createdAt = createdAt;
        return entity;
    }
}
