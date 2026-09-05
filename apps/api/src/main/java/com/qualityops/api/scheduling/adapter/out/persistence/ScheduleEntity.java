package com.qualityops.api.scheduling.adapter.out.persistence;

import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.scheduling.domain.CatchUpPolicy;
import com.qualityops.api.scheduling.domain.Schedule;
import com.qualityops.api.scheduling.domain.ScheduleKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** VARCHAR + CHECK columns -> plain STRING enums (NOT NAMED_ENUM). */
@Entity
@Table(name = "schedule")
class ScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScheduleKind kind;

    @Column(name = "cron_expression", length = 120)
    private String cronExpression;

    @Column(name = "time_zone", length = 64)
    private String timeZone;

    @Column(name = "fire_at")
    private Instant fireAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "catch_up_policy", nullable = false, length = 16)
    private CatchUpPolicy catchUpPolicy;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "next_fire_at")
    private Instant nextFireAt;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScheduleEntity() {}

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    static ScheduleEntity fromDomain(Schedule s) {
        var e = new ScheduleEntity();
        e.id = s.id();
        e.orgId = s.orgId();
        e.projectId = s.projectId();
        e.suiteId = s.suiteId();
        e.environmentId = s.environmentId();
        e.name = s.name();
        e.kind = s.kind();
        e.cronExpression = s.cronExpression();
        e.timeZone = s.timeZone();
        e.fireAt = s.fireAt();
        e.priority = s.priority();
        e.catchUpPolicy = s.catchUpPolicy();
        e.enabled = s.enabled();
        e.nextFireAt = s.nextFireAt();
        e.lastFiredAt = s.lastFiredAt();
        e.lastError = s.lastError();
        e.lastErrorAt = s.lastErrorAt();
        e.createdBy = s.createdBy();
        e.createdAt = s.createdAt();
        e.updatedAt = s.updatedAt();
        return e;
    }

    Schedule toDomain() {
        return new Schedule(id, orgId, projectId, suiteId, environmentId, name, kind, cronExpression,
            timeZone, fireAt, priority, catchUpPolicy, enabled, nextFireAt, lastFiredAt, lastError,
            lastErrorAt, createdBy, createdAt, updatedAt);
    }
}
