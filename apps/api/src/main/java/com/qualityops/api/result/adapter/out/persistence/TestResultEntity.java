package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.result.domain.ResultStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_results")
class TestResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "test_case_id", nullable = false)
    private UUID testCaseId;

    // @JdbcTypeCode(NAMED_ENUM) tells Hibernate 6 this is a PostgreSQL named enum,
    // preventing a type-mismatch during ddl-auto:validate.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ResultStatus status;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "attempt_epoch", nullable = false)
    private int attemptEpoch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TestResultEntity() {}

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

    UUID getRunId() {
        return runId;
    }

    UUID getTestCaseId() {
        return testCaseId;
    }

    ResultStatus getStatus() {
        return status;
    }

    int getDurationMs() {
        return durationMs;
    }

    String getErrorMessage() {
        return errorMessage;
    }

    int getRetryCount() {
        return retryCount;
    }

    int getAttemptEpoch() {
        return attemptEpoch;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    static TestResultEntity create(UUID id, UUID orgId, UUID runId, UUID testCaseId, ResultStatus status,
                                    int durationMs, String errorMessage, int retryCount, int attemptEpoch,
                                    Instant createdAt) {
        var entity = new TestResultEntity();
        entity.id = id;
        entity.orgId = orgId;
        entity.runId = runId;
        entity.testCaseId = testCaseId;
        entity.status = status;
        entity.durationMs = durationMs;
        entity.errorMessage = errorMessage;
        entity.retryCount = retryCount;
        entity.attemptEpoch = attemptEpoch;
        entity.createdAt = createdAt;
        return entity;
    }
}
