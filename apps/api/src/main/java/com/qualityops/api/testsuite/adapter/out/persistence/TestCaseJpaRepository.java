package com.qualityops.api.testsuite.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TestCaseJpaRepository extends JpaRepository<TestCaseEntity, UUID> {

    Optional<TestCaseEntity> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    Page<TestCaseEntity> findAllBySuiteIdAndOrgIdAndDeletedAtIsNull(UUID suiteId, UUID orgId, Pageable pageable);

    List<TestCaseEntity> findAllBySuiteIdAndOrgIdAndDeletedAtIsNullOrderByOrderIndexAsc(UUID suiteId, UUID orgId);

    @Query("SELECT MAX(c.orderIndex) FROM TestCaseEntity c " +
        "WHERE c.suiteId = :suiteId AND c.orgId = :orgId AND c.deletedAt IS NULL")
    Optional<Integer> findMaxOrderIndexBySuiteId(@Param("suiteId") UUID suiteId, @Param("orgId") UUID orgId);

    // ADR-009 §11 — native: repo_test is a jsonb column; ->> extracts the
    // connection id as text for the referenced-by-any-case delete guard.
    @Query(value = "SELECT count(*) FROM test_cases WHERE org_id = :orgId AND deleted_at IS NULL "
        + "AND repo_test IS NOT NULL AND repo_test ->> 'repositoryConnectionId' = :connectionId",
        nativeQuery = true)
    long countReferencingConnection(@Param("orgId") UUID orgId, @Param("connectionId") String connectionId);

    @Modifying
    @Query("UPDATE TestCaseEntity c SET c.deletedAt = :deletedAt, c.updatedAt = :deletedAt " +
        "WHERE c.id = :id AND c.orgId = :orgId")
    void softDelete(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("deletedAt") Instant deletedAt);
}
