package com.qualityops.api.execution.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Per-org override of the global max-active-runs cap (ADR-006 §4.2). 2C reads
 *  only; the write API/UI is 2D+. */
@Entity
@Table(name = "org_run_concurrency")
class OrgRunConcurrencyEntity {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "max_active_runs", nullable = false)
    private int maxActiveRuns;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrgRunConcurrencyEntity() {}

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    UUID getOrgId() {
        return orgId;
    }

    int getMaxActiveRuns() {
        return maxActiveRuns;
    }
}
