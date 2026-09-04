package com.qualityops.api.result.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** ADR-009 §7 (V25) — read-path mapping only. Writes go through the native
 *  epoch-guarded upsert in {@link RepositoryTestItemJpaRepository}. */
@Entity
@Table(name = "repository_test_item")
class RepositoryTestItemEntity {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "item_key", nullable = false)
    private String itemKey;

    @Column(name = "suite")
    private String suite;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "failure_type")
    private String failureType;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "attempt_epoch", nullable = false)
    private int attemptEpoch;

    protected RepositoryTestItemEntity() {}

    UUID getRunId() {
        return runId;
    }

    String getItemKey() {
        return itemKey;
    }

    String getSuite() {
        return suite;
    }

    String getName() {
        return name;
    }

    String getStatus() {
        return status;
    }

    Integer getDurationMs() {
        return durationMs;
    }

    String getFailureType() {
        return failureType;
    }

    String getFailureMessage() {
        return failureMessage;
    }

    int getAttemptEpoch() {
        return attemptEpoch;
    }
}
