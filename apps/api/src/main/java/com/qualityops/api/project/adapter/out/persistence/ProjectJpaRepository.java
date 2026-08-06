package com.qualityops.api.project.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface ProjectJpaRepository extends JpaRepository<ProjectEntity, UUID> {

    Optional<ProjectEntity> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);

    Page<ProjectEntity> findAllByOrgIdAndDeletedAtIsNull(UUID orgId, Pageable pageable);

    @Modifying
    @Query("UPDATE ProjectEntity p SET p.deletedAt = :deletedAt, p.updatedAt = :deletedAt " +
        "WHERE p.id = :id AND p.orgId = :orgId")
    void softDelete(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("deletedAt") Instant deletedAt);
}
