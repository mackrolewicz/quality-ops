package com.qualityops.api.testsuite.adapter.out.persistence;

import com.qualityops.api.testsuite.domain.TestCase;
import jakarta.persistence.*;

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

    TestCase toDomain() {
        return new TestCase(id, orgId, suiteId, name, description, orderIndex, createdAt, updatedAt, deletedAt);
    }

    static TestCaseEntity fromDomain(TestCase testCase) {
        var entity = new TestCaseEntity();
        entity.id = testCase.id();
        entity.orgId = testCase.orgId();
        entity.suiteId = testCase.suiteId();
        entity.name = testCase.name();
        entity.description = testCase.description();
        entity.orderIndex = testCase.orderIndex();
        entity.deletedAt = testCase.deletedAt();
        return entity;
    }
}
