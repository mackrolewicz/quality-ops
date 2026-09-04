package com.qualityops.api.execution.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RepositoryRunJpaRepository extends JpaRepository<RepositoryRunEntity, UUID> {

    Optional<RepositoryRunEntity> findByRunIdAndOrgId(UUID runId, UUID orgId);

    List<RepositoryRunEntity> findByOrgIdAndRunIdIn(UUID orgId, List<UUID> runIds);

    // Frozen columns only (domain rule #2). state defaults to 'PENDING'.
    @Modifying
    @Query(value = """
        INSERT INTO repository_run (
            id, org_id, run_id, repository_connection_id, provider, repo_host, repo_path,
            requested_ref, commit_sha, ref_type, framework_preset, runner_image_ref, working_dir,
            command_json, report_format, report_paths_json, artifact_globs_json, resource_profile,
            network_policy, timeout_seconds, state, created_at)
        VALUES (
            gen_random_uuid(), :orgId, :runId, :connId, :provider, :repoHost, :repoPath,
            :requestedRef, :commitSha, :refType, :framework, :imageRef, :workingDir,
            CAST(:commandJson AS jsonb), :reportFormat, CAST(:reportPathsJson AS jsonb),
            CAST(:artifactGlobsJson AS jsonb), :resourceProfile, :networkPolicy, :timeoutSeconds,
            'PENDING', now())
        """, nativeQuery = true)
    void insertFrozen(@Param("orgId") UUID orgId, @Param("runId") UUID runId,
                      @Param("connId") UUID connId, @Param("provider") String provider,
                      @Param("repoHost") String repoHost, @Param("repoPath") String repoPath,
                      @Param("requestedRef") String requestedRef, @Param("commitSha") String commitSha,
                      @Param("refType") String refType, @Param("framework") String framework,
                      @Param("imageRef") String imageRef, @Param("workingDir") String workingDir,
                      @Param("commandJson") String commandJson, @Param("reportFormat") String reportFormat,
                      @Param("reportPathsJson") String reportPathsJson,
                      @Param("artifactGlobsJson") String artifactGlobsJson,
                      @Param("resourceProfile") String resourceProfile,
                      @Param("networkPolicy") String networkPolicy,
                      @Param("timeoutSeconds") int timeoutSeconds);

    // org- + executionId-guarded state advance. A stale/foreign event or a
    // non-repo run leaves 0 rows — the redelivery-safe no-op.
    @Modifying
    @Query(value = """
        UPDATE repository_run rr SET state = :toState
        WHERE rr.run_id = :runId AND rr.org_id = :orgId AND rr.state IN (:fromStates)
          AND EXISTS (SELECT 1 FROM test_runs t
                      WHERE t.id = rr.run_id AND t.org_id = rr.org_id AND t.execution_id = :executionId)
        """, nativeQuery = true)
    int transitionState(@Param("runId") UUID runId, @Param("orgId") UUID orgId,
                        @Param("executionId") UUID executionId,
                        @Param("fromStates") List<String> fromStates, @Param("toState") String toState);

    // Telemetry: counts/digest/exit — new value wins when non-null; checkout/started
    // timestamps are keep-first; finished — new wins when non-null. Skipped for a
    // CANCELLED row; org- + executionId-guarded.
    @Modifying
    @Query(value = """
        UPDATE repository_run rr SET
            runner_image_digest = COALESCE(CAST(:imageDigest AS varchar), rr.runner_image_digest),
            container_exit_code = COALESCE(CAST(:exitCode AS integer), rr.container_exit_code),
            items_total   = COALESCE(CAST(:itemsTotal AS integer), rr.items_total),
            items_passed  = COALESCE(CAST(:itemsPassed AS integer), rr.items_passed),
            items_failed  = COALESCE(CAST(:itemsFailed AS integer), rr.items_failed),
            items_skipped = COALESCE(CAST(:itemsSkipped AS integer), rr.items_skipped),
            checkout_at = COALESCE(rr.checkout_at, CAST(:checkoutAt AS timestamptz)),
            started_at  = COALESCE(rr.started_at,  CAST(:startedAt AS timestamptz)),
            finished_at = COALESCE(CAST(:finishedAt AS timestamptz), rr.finished_at)
        WHERE rr.run_id = :runId AND rr.org_id = :orgId AND rr.state <> 'CANCELLED'
          AND EXISTS (SELECT 1 FROM test_runs t
                      WHERE t.id = rr.run_id AND t.org_id = rr.org_id AND t.execution_id = :executionId)
        """, nativeQuery = true)
    int applyTelemetry(@Param("runId") UUID runId, @Param("orgId") UUID orgId,
                       @Param("executionId") UUID executionId,
                       @Param("imageDigest") String imageDigest, @Param("exitCode") Integer exitCode,
                       @Param("itemsTotal") Integer itemsTotal, @Param("itemsPassed") Integer itemsPassed,
                       @Param("itemsFailed") Integer itemsFailed, @Param("itemsSkipped") Integer itemsSkipped,
                       @Param("checkoutAt") Instant checkoutAt, @Param("startedAt") Instant startedAt,
                       @Param("finishedAt") Instant finishedAt);
}
