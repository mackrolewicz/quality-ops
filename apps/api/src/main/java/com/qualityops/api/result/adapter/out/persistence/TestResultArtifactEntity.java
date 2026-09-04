package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.result.domain.ArtifactAvailability;
import com.qualityops.api.result.domain.ArtifactType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Mutable JPA holder for {@code test_result_artifacts}. {@code artifact_type}
 *  and {@code status} are plain VARCHAR columns (not PostgreSQL enums). */
@Entity
@Table(name = "test_result_artifacts")
class TestResultArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "test_case_id", nullable = false)
    private UUID testCaseId;

    @Column(name = "attempt_epoch", nullable = false)
    private int attemptEpoch;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 24)
    private ArtifactType artifactType;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ArtifactAvailability status;

    @Column(name = "unavailable_reason", length = 64)
    private String unavailableReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TestResultArtifactEntity() {}

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

    int getAttemptEpoch() {
        return attemptEpoch;
    }

    ArtifactType getArtifactType() {
        return artifactType;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getContentType() {
        return contentType;
    }

    Long getSizeBytes() {
        return sizeBytes;
    }

    ArtifactAvailability getStatus() {
        return status;
    }

    String getUnavailableReason() {
        return unavailableReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
