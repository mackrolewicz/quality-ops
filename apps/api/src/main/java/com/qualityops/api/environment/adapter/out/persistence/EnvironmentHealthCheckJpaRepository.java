package com.qualityops.api.environment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface EnvironmentHealthCheckJpaRepository extends JpaRepository<EnvironmentHealthCheckEntity, UUID> {

    List<EnvironmentHealthCheckEntity> findTop20ByEnvironmentIdAndOrgIdOrderByCheckedAtDesc(
        UUID environmentId, UUID orgId);

    @Modifying
    @Query("delete from EnvironmentHealthCheckEntity e where e.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
