package com.qualityops.api.execution.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OrgRunConcurrencyJpaRepository extends JpaRepository<OrgRunConcurrencyEntity, UUID> {

    @Query("SELECT o.orgId, o.maxActiveRuns FROM OrgRunConcurrencyEntity o")
    List<Object[]> findAllLimits();

    @Query("SELECT o.maxActiveRuns FROM OrgRunConcurrencyEntity o WHERE o.orgId = :orgId")
    Optional<Integer> findMaxActiveRunsByOrgId(@Param("orgId") UUID orgId);

    @Modifying
    @Query(value = """
        INSERT INTO org_run_concurrency (org_id, max_active_runs, created_at, updated_at)
        VALUES (:orgId, :max, now(), now())
        ON CONFLICT (org_id) DO UPDATE SET max_active_runs = EXCLUDED.max_active_runs, updated_at = now()
        """, nativeQuery = true)
    void upsert(@Param("orgId") UUID orgId, @Param("max") int max);
}
