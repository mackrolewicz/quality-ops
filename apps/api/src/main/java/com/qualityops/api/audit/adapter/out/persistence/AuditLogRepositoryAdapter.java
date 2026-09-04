package com.qualityops.api.audit.adapter.out.persistence;

import com.qualityops.api.audit.application.port.out.AuditLogRepository;
import org.springframework.stereotype.Repository;

/** JPA adapter for {@link AuditLogRepository} (ADR-008 &sect;7). */
@Repository
public class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final AuditLogJpaRepository jpa;

    AuditLogRepositoryAdapter(AuditLogJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void insert(AuditLogRow row) {
        jpa.save(AuditLogEntity.fromRow(row));
    }
}
