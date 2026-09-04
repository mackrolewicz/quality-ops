package com.qualityops.api.webhook.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface WebhookEndpointJpaRepository extends JpaRepository<WebhookEndpointEntity, UUID> {

    List<WebhookEndpointEntity> findByOrgIdAndProjectIdOrderByCreatedAtAsc(UUID orgId, UUID projectId);

    Optional<WebhookEndpointEntity> findByIdAndOrgId(UUID id, UUID orgId);

    @Modifying
    @Query("DELETE FROM WebhookEndpointEntity e WHERE e.id = :id AND e.orgId = :orgId")
    int deleteByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("SELECT e FROM WebhookEndpointEntity e WHERE e.orgId = :orgId AND e.enabled = true "
        + "AND (e.projectId = :projectId OR e.projectId IS NULL)")
    List<WebhookEndpointEntity> findEnabledForOrgAndProject(@Param("orgId") UUID orgId,
                                                            @Param("projectId") UUID projectId);
}
