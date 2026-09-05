package com.qualityops.api.testsuite.adapter.out.persistence;

import com.qualityops.api.testsuite.domain.TestCase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_cases")
class TestCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    // Pre-serialised JSON string; Jackson (de)serialisation happens in
    // TestCaseRepositoryAdapter, not here, to keep this entity a dumb holder.
    @Column(name = "api_request", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String apiRequestJson;

    @Column(name = "browser_test", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String browserTestJson;

    @Column(name = "repo_test", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String repoTestJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected TestCaseEntity() {}

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getSuiteId() {
        return suiteId;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    int getOrderIndex() {
        return orderIndex;
    }

    String getApiRequestJson() {
        return apiRequestJson;
    }

    String getBrowserTestJson() {
        return browserTestJson;
    }

    String getRepoTestJson() {
        return repoTestJson;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }

    static TestCaseEntity fromDomain(TestCase testCase, String apiRequestJson, String browserTestJson,
                                     String repoTestJson) {
        var entity = new TestCaseEntity();
        entity.id = testCase.id();
        entity.orgId = testCase.orgId();
        entity.suiteId = testCase.suiteId();
        entity.name = testCase.name();
        entity.description = testCase.description();
        entity.orderIndex = testCase.orderIndex();
        entity.apiRequestJson = apiRequestJson;
        entity.browserTestJson = browserTestJson;
        entity.repoTestJson = repoTestJson;
        entity.deletedAt = testCase.deletedAt();
        return entity;
    }
}
