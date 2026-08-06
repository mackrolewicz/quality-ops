package com.qualityops.api.result.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface TestResultJpaRepository extends JpaRepository<TestResultEntity, UUID> {

    Page<TestResultEntity> findAllByRunIdAndOrgId(UUID runId, UUID orgId, Pageable pageable);

    boolean existsByRunIdAndOrgId(UUID runId, UUID orgId);
}
