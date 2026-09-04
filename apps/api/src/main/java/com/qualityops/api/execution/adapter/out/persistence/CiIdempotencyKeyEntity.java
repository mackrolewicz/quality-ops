package com.qualityops.api.execution.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** ADR-007 §5.3. {@code (org_id, idempotency_key)} is UNIQUE — the arbiter for a
 *  concurrent first-call race. */
@Entity
@Table(name = "ci_idempotency_key")
class CiIdempotencyKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CiIdempotencyKeyEntity() {}

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

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRequestFingerprint() {
        return requestFingerprint;
    }

    UUID getRunId() {
        return runId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    static CiIdempotencyKeyEntity create(UUID orgId, String idempotencyKey,
                                         String requestFingerprint, UUID runId) {
        var entity = new CiIdempotencyKeyEntity();
        entity.orgId = orgId;
        entity.idempotencyKey = idempotencyKey;
        entity.requestFingerprint = requestFingerprint;
        entity.runId = runId;
        return entity;
    }
}
