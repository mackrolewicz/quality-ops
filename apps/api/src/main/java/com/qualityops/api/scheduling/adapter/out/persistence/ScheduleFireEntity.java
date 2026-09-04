package com.qualityops.api.scheduling.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Per-occurrence dedup ledger. UNIQUE (schedule_id, fire_slot) in V14. */
@Entity
@Table(name = "schedule_fire")
class ScheduleFireEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "fire_slot", nullable = false, updatable = false)
    private Instant fireSlot;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScheduleFireEntity() {}

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
