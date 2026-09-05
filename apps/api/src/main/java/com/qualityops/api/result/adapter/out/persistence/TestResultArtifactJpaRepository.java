package com.qualityops.api.result.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface TestResultArtifactJpaRepository extends JpaRepository<TestResultArtifactEntity, UUID> {

    Page<TestResultArtifactEntity> findAllByRunIdAndOrgId(UUID runId, UUID orgId, Pageable pageable);

    java.util.Optional<TestResultArtifactEntity> findByIdAndOrgId(UUID id, UUID orgId);

    @Query(value = "SELECT COALESCE(MAX(attempt_epoch), -1) FROM test_result_artifacts "
        + "WHERE org_id = :orgId AND run_id = :runId AND test_case_id = :testCaseId", nativeQuery = true)
    int currentMaxEpoch(@Param("orgId") UUID orgId,
                        @Param("runId") UUID runId,
                        @Param("testCaseId") UUID testCaseId);

    @Modifying
    @Query(value = """
        INSERT INTO test_result_artifacts
            (id, org_id, run_id, test_case_id, attempt_epoch, artifact_type,
             storage_key, content_type, size_bytes, status, unavailable_reason, created_at)
        VALUES
            (gen_random_uuid(), :orgId, :runId, :testCaseId, :attemptEpoch, :artifactType,
             :storageKey, :contentType, :sizeBytes, :status, :unavailableReason, now())
        ON CONFLICT (run_id, test_case_id, attempt_epoch, artifact_type) DO UPDATE SET
            org_id             = EXCLUDED.org_id,
            storage_key        = EXCLUDED.storage_key,
            content_type       = EXCLUDED.content_type,
            size_bytes         = EXCLUDED.size_bytes,
            status             = EXCLUDED.status,
            unavailable_reason = EXCLUDED.unavailable_reason
        """, nativeQuery = true)
    void upsertOne(@Param("orgId") UUID orgId,
                   @Param("runId") UUID runId,
                   @Param("testCaseId") UUID testCaseId,
                   @Param("attemptEpoch") int attemptEpoch,
                   @Param("artifactType") String artifactType,
                   @Param("storageKey") String storageKey,
                   @Param("contentType") String contentType,
                   @Param("sizeBytes") Long sizeBytes,
                   @Param("status") String status,
                   @Param("unavailableReason") String unavailableReason);

    @Modifying
    @Query(value = "DELETE FROM test_result_artifacts "
        + "WHERE org_id = :orgId AND run_id = :runId AND test_case_id = :testCaseId "
        + "AND attempt_epoch < :attemptEpoch",
        nativeQuery = true)
    void deleteLowerEpochs(@Param("orgId") UUID orgId,
                           @Param("runId") UUID runId,
                           @Param("testCaseId") UUID testCaseId,
                           @Param("attemptEpoch") int attemptEpoch);
}
