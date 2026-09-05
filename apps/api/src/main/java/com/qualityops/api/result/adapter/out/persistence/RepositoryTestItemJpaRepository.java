package com.qualityops.api.result.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface RepositoryTestItemJpaRepository extends JpaRepository<RepositoryTestItemEntity, UUID> {

    List<RepositoryTestItemEntity> findByRunIdAndOrgIdOrderBySuiteAscNameAsc(UUID runId, UUID orgId);

    // ADR-005 §2.4 pattern — epoch-monotone upsert. A lower-epoch redelivery is a
    // no-op (the WHERE on the DO UPDATE fails). status is a plain VARCHAR label.
    @Modifying
    @Query(value = """
        INSERT INTO repository_test_item
            (id, org_id, run_id, item_key, suite, name, status, duration_ms,
             failure_type, failure_message, attempt_epoch, created_at)
        VALUES
            (gen_random_uuid(), :orgId, :runId, :itemKey, :suite, :name, :status,
             CAST(:durationMs AS integer), :failureType, :failureMessage, :attemptEpoch, now())
        ON CONFLICT (run_id, item_key) DO UPDATE SET
            suite           = EXCLUDED.suite,
            name            = EXCLUDED.name,
            status          = EXCLUDED.status,
            duration_ms     = EXCLUDED.duration_ms,
            failure_type    = EXCLUDED.failure_type,
            failure_message = EXCLUDED.failure_message,
            attempt_epoch   = EXCLUDED.attempt_epoch
        WHERE repository_test_item.attempt_epoch <= EXCLUDED.attempt_epoch
        """, nativeQuery = true)
    void upsertItem(@Param("orgId") UUID orgId, @Param("runId") UUID runId,
                    @Param("itemKey") String itemKey, @Param("suite") String suite,
                    @Param("name") String name, @Param("status") String status,
                    @Param("durationMs") Integer durationMs, @Param("failureType") String failureType,
                    @Param("failureMessage") String failureMessage, @Param("attemptEpoch") int attemptEpoch);
}
