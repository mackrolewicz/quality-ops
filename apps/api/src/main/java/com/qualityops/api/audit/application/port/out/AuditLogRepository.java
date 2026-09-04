package com.qualityops.api.audit.application.port.out;

import com.qualityops.api.audit.domain.AuditOutcome;

import java.time.Instant;
import java.util.UUID;

/** Output port for persisting {@code audit_log} rows (ADR-008 &sect;7). */
public interface AuditLogRepository {

    void insert(AuditLogRow row);

    record AuditLogRow(UUID orgId, UUID actorUserId, String action, String targetType,
                       UUID targetId, AuditOutcome outcome, String detailJson, Instant createdAt) {
    }
}
