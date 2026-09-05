package com.qualityops.api.result.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface TestResultJpaRepository extends JpaRepository<TestResultEntity, UUID> {

    Page<TestResultEntity> findAllByRunIdAndOrgId(UUID runId, UUID orgId, Pageable pageable);

    @Modifying
    @Query(value = """
        INSERT INTO test_results
            (id, org_id, run_id, test_case_id, status, duration_ms, error_message,
             retry_count, attempt_epoch, created_at)
        VALUES
            (gen_random_uuid(), :orgId, :runId, :testCaseId, CAST(:status AS result_status),
             :durationMs, :errorMessage, :retryCount, :attemptEpoch, now())
        ON CONFLICT (run_id, test_case_id) DO UPDATE SET
            status        = EXCLUDED.status,
            duration_ms   = EXCLUDED.duration_ms,
            error_message = EXCLUDED.error_message,
            retry_count   = EXCLUDED.retry_count,
            attempt_epoch = EXCLUDED.attempt_epoch
        WHERE test_results.attempt_epoch <= EXCLUDED.attempt_epoch
        """, nativeQuery = true)
    void upsertCaseResult(@Param("orgId") UUID orgId,
                          @Param("runId") UUID runId,
                          @Param("testCaseId") UUID testCaseId,
                          @Param("status") String status,
                          @Param("durationMs") int durationMs,
                          @Param("errorMessage") String errorMessage,
                          @Param("retryCount") int retryCount,
                          @Param("attemptEpoch") int attemptEpoch);
}
