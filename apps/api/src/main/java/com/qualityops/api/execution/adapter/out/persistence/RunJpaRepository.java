package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.domain.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface RunJpaRepository extends JpaRepository<RunEntity, UUID> {

    Optional<RunEntity> findByIdAndOrgId(UUID id, UUID orgId);

    @Query("SELECT r FROM RunEntity r WHERE r.orgId = :orgId " +
        "AND (:projectId IS NULL OR r.projectId = :projectId) " +
        "AND (:suiteId IS NULL OR r.suiteId = :suiteId) " +
        "AND (:status IS NULL OR r.status = :status)")
    Page<RunEntity> findAllByOrgId(@Param("orgId") UUID orgId,
                                    @Param("projectId") UUID projectId,
                                    @Param("suiteId") UUID suiteId,
                                    @Param("status") RunStatus status,
                                    Pageable pageable);

    // Two separate conditional UPDATEs (rather than a single JPQL CASE
    // expression) to avoid brittle enum-vs-string comparisons in JPQL.
    // Each is atomic and keyed on id + fromStatus, satisfying the
    // idempotency contract in RunRepository#transitionStatus.
    @Modifying
    @Query("UPDATE RunEntity r SET r.status = :toStatus, r.startedAt = :timestamp " +
        "WHERE r.id = :runId AND r.status = :fromStatus")
    int markRunning(@Param("runId") UUID runId,
                     @Param("fromStatus") RunStatus fromStatus,
                     @Param("toStatus") RunStatus toStatus,
                     @Param("timestamp") Instant timestamp);

    @Modifying
    @Query("UPDATE RunEntity r SET r.status = :toStatus, r.completedAt = :timestamp " +
        "WHERE r.id = :runId AND r.status = :fromStatus")
    int markResolved(@Param("runId") UUID runId,
                      @Param("fromStatus") RunStatus fromStatus,
                      @Param("toStatus") RunStatus toStatus,
                      @Param("timestamp") Instant timestamp);

    @Query(value = "SELECT COUNT(*) AS totalRuns, " +
        "COUNT(*) FILTER (WHERE status = 'PASSED') AS passedRuns, " +
        "COUNT(*) FILTER (WHERE status = 'FAILED') AS failedRuns " +
        "FROM test_runs WHERE org_id = :orgId AND project_id = :projectId AND created_at >= :since " +
        "AND status IN ('PASSED','FAILED')",
        nativeQuery = true)
    RunStatsProjection getStats(@Param("orgId") UUID orgId,
                                 @Param("projectId") UUID projectId,
                                 @Param("since") Instant since);
}
