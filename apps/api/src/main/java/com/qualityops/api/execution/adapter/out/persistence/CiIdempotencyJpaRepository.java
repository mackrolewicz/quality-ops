package com.qualityops.api.execution.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface CiIdempotencyJpaRepository extends JpaRepository<CiIdempotencyKeyEntity, UUID> {

    Optional<CiIdempotencyKeyEntity> findByOrgIdAndIdempotencyKey(UUID orgId, String idempotencyKey);

    @Modifying
    @Query(value = "DELETE FROM ci_idempotency_key WHERE created_at < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
