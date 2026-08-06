package com.qualityops.api.testsuite.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface TestSuiteJpaRepository extends JpaRepository<TestSuiteEntity, UUID> {

    Optional<TestSuiteEntity> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    Page<TestSuiteEntity> findAllByProjectIdAndOrgIdAndDeletedAtIsNull(UUID projectId, UUID orgId, Pageable pageable);

    @Modifying
    @Query("UPDATE TestSuiteEntity s SET s.deletedAt = :deletedAt, s.updatedAt = :deletedAt " +
        "WHERE s.id = :id AND s.orgId = :orgId")
    void softDelete(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("deletedAt") Instant deletedAt);
}
