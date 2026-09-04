package com.qualityops.api.audit.adapter.out.persistence;

import com.qualityops.api.audit.application.port.out.AuditLogRepository.AuditLogRow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** ADR-008 &sect;7 (V21). {@code outcome} is VARCHAR + CHECK -&gt; plain String;
 *  {@code detail} is jsonb. Written only by {@code AuditRecorder}, never updated. */
@Entity
@Table(name = "audit_log")
class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private String action;

    @Column(name = "target_type", length = 64, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private String outcome;

    @Column(name = "detail", columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    static AuditLogEntity fromRow(AuditLogRow row) {
        var entity = new AuditLogEntity();
        entity.orgId = row.orgId();
        entity.actorUserId = row.actorUserId();
        entity.action = row.action();
        entity.targetType = row.targetType();
        entity.targetId = row.targetId();
        entity.outcome = row.outcome().name();
        entity.detail = row.detailJson();
        entity.createdAt = row.createdAt();
        return entity;
    }

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

    UUID getActorUserId() {
        return actorUserId;
    }

    String getAction() {
        return action;
    }

    String getTargetType() {
        return targetType;
    }

    UUID getTargetId() {
        return targetId;
    }

    String getOutcome() {
        return outcome;
    }

    String getDetail() {
        return detail;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
