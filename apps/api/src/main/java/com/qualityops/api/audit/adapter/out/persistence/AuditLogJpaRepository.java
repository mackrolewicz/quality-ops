package com.qualityops.api.audit.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {

    /** Test-only: org-scoped read for {@code AuditLogRepositoryIT} multi-tenancy assertions. */
    List<AuditLogEntity> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
