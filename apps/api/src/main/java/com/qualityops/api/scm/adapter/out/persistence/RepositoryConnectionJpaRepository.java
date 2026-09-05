package com.qualityops.api.scm.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RepositoryConnectionJpaRepository extends JpaRepository<RepositoryConnectionEntity, UUID> {

    Optional<RepositoryConnectionEntity> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    List<RepositoryConnectionEntity> findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCreatedAtAsc(
        UUID orgId, UUID projectId);

    @Modifying
    @Query("UPDATE RepositoryConnectionEntity e SET e.deletedAt = :ts, e.updatedAt = :ts "
        + "WHERE e.id = :id AND e.orgId = :orgId AND e.deletedAt IS NULL")
    int softDelete(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("ts") Instant ts);
}
