package com.qualityops.api.audit.adapter.out.persistence;

import com.qualityops.api.audit.application.port.out.AuditLogRepository;
import com.qualityops.api.audit.application.port.out.AuditLogRepository.AuditLogRow;
import com.qualityops.api.audit.domain.AuditOutcome;
import com.qualityops.api.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 §7 — {@code audit_log} rows are org-scoped and tenant-isolated. */
class AuditLogRepositoryIT extends AbstractPostgresIT {

    @Autowired private AuditLogRepository repository;
    @Autowired private AuditLogJpaRepository jpa;

    @Test
    void insert_row_isPersistedWithOrgId() {
        UUID orgA = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        repository.insert(new AuditLogRow(orgA, actor, "environment.create", "environment",
            target, AuditOutcome.SUCCESS, "{\"k\":\"v\"}", Instant.now()));

        var rows = jpa.findByOrgIdOrderByCreatedAtDesc(orgA);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.getOrgId()).isEqualTo(orgA);
        assertThat(row.getActorUserId()).isEqualTo(actor);
        assertThat(row.getAction()).isEqualTo("environment.create");
        assertThat(row.getTargetType()).isEqualTo("environment");
        assertThat(row.getTargetId()).isEqualTo(target);
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
        assertThat(row.getDetail()).contains("\"k\"").contains("\"v\"");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void insert_rowForOrgA_invisibleToOrgBQuery() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        repository.insert(new AuditLogRow(orgA, null, "project.delete", "project",
            UUID.randomUUID(), AuditOutcome.SUCCESS, null, Instant.now()));

        assertThat(jpa.findByOrgIdOrderByCreatedAtDesc(orgB)).isEmpty();
    }
}
