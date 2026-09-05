package com.qualityops.api.audit.application;

import com.qualityops.api.audit.application.port.out.AuditLogRepository;
import com.qualityops.api.audit.application.port.out.AuditLogRepository.AuditLogRow;
import com.qualityops.api.audit.domain.AuditOutcome;
import com.qualityops.api.config.QueueMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes one {@code audit_log} row per {@code @Audited} call (ADR-008 &sect;7).
 *
 * <p>Runs in {@link Propagation#REQUIRES_NEW} so the row survives a later
 * rollback of the business transaction, and swallows {@link DataAccessException}
 * so an audit-store problem never breaks the business call. Stated tradeoff: an
 * action whose transaction later rolls back can still leave a {@code SUCCESS}
 * row &mdash; acceptable for a lab.
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditLogRepository repository;
    private final QueueMetrics metrics;

    public AuditRecorder(AuditLogRepository repository, QueueMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orgId, UUID actorUserId, String action, String targetType,
                       UUID targetId, AuditOutcome outcome, String detailJson) {
        try {
            repository.insert(new AuditLogRow(orgId, actorUserId, action, emptyToNull(targetType),
                targetId, outcome, detailJson, Instant.now()));
            metrics.auditWritten(outcome.name());
        } catch (DataAccessException e) {
            log.warn("audit write failed action={} target={} - business call unaffected",
                action, targetId, e);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
