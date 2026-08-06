package com.qualityops.api.testsuite.adapter.out.persistence;

import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestSuite;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_suites")
class TestSuiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    // @JdbcTypeCode(NAMED_ENUM) tells Hibernate 6 this is a PostgreSQL named enum,
    // preventing a type-mismatch during ddl-auto:validate.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private SuiteType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected TestSuiteEntity() {}

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    TestSuite toDomain() {
        return new TestSuite(id, orgId, projectId, name, description, type, createdAt, updatedAt, deletedAt);
    }

    static TestSuiteEntity fromDomain(TestSuite suite) {
        var entity = new TestSuiteEntity();
        entity.id = suite.id();
        entity.orgId = suite.orgId();
        entity.projectId = suite.projectId();
        entity.name = suite.name();
        entity.description = suite.description();
        entity.type = suite.type();
        entity.deletedAt = suite.deletedAt();
        return entity;
    }
}
