package com.qualityops.api.environment.adapter.out.persistence;

import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "environments")
class EnvironmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    // @JdbcTypeCode(NAMED_ENUM) tells Hibernate 6 this is a PostgreSQL named enum,
    // preventing a type-mismatch during ddl-auto:validate.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private EnvironmentType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private EnvironmentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected EnvironmentEntity() {}

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    Environment toDomain() {
        return new Environment(id, orgId, projectId, name, baseUrl, type, status, createdAt, updatedAt, deletedAt);
    }

    static EnvironmentEntity fromDomain(Environment environment) {
        var entity = new EnvironmentEntity();
        entity.id = environment.id();
        entity.orgId = environment.orgId();
        entity.projectId = environment.projectId();
        entity.name = environment.name();
        entity.baseUrl = environment.baseUrl();
        entity.type = environment.type();
        entity.status = environment.status();
        entity.deletedAt = environment.deletedAt();
        return entity;
    }
}
