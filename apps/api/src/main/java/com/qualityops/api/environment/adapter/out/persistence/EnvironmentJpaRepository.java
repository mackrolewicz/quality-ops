package com.qualityops.api.environment.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface EnvironmentJpaRepository extends JpaRepository<EnvironmentEntity, UUID> {

    Optional<EnvironmentEntity> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    Page<EnvironmentEntity> findAllByProjectIdAndOrgIdAndDeletedAtIsNull(UUID projectId, UUID orgId, Pageable pageable);

    @Modifying
    @Query("UPDATE EnvironmentEntity e SET e.deletedAt = :deletedAt, e.updatedAt = :deletedAt " +
        "WHERE e.id = :id AND e.orgId = :orgId")
    void softDelete(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("deletedAt") Instant deletedAt);
}
