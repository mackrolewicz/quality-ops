package com.qualityops.api.result;

import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.api.result.application.port.in.RecordCaseResultChunkUseCase;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RepositoryRunProvenance;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.RunStartedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §13 — {@code repository_run} telemetry: state advances on the
 *  lifecycle events, provenance fills the counts/digest/exit columns, and a
 *  stale-executionId event is a 0-row no-op. */
class RepositoryRunTelemetryIT extends AbstractPostgresIT {

    @Autowired private ApplyRunLifecycleUseCase lifecycle;
    @Autowired private RecordCaseResultChunkUseCase results;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID runId;
    private UUID executionId;
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        var projectId = ItFixtures.insertProject(jdbc, orgId);
        var suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        var environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        var triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        jdbc.update("INSERT INTO test_cases (id, suite_id, org_id, name, order_index) VALUES (?, ?, ?, ?, 0)",
            caseId, suiteId, orgId, "repo case");
        executionId = UUID.randomUUID();
        runId = jdbc.queryForObject(
            "INSERT INTO test_runs (org_id, project_id, suite_id, environment_id, status, triggered_by, "
                + "config_snapshot, execution_id, created_at) "
                + "VALUES (?, ?, ?, ?, 'PENDING'::run_status, ?, '{\"cases\":[]}'::jsonb, ?, now()) RETURNING id",
            UUID.class, orgId, projectId, suiteId, environmentId, triggeredBy, executionId);
        var connectionId = jdbc.queryForObject(
            "INSERT INTO repository_connection (org_id, project_id, provider, host, owner_path, repo_name, "
                + "default_ref, created_by) VALUES (?, ?, 'GITHUB', 'github.com', 'acme', 'web', 'main', ?) "
                + "RETURNING id",
            UUID.class, orgId, projectId, triggeredBy);
        jdbc.update("INSERT INTO repository_run (org_id, run_id, repository_connection_id, provider, "
                + "repo_host, repo_path, requested_ref, commit_sha, ref_type, framework_preset, "
                + "runner_image_ref, command_json, report_format, resource_profile, network_policy, "
                + "timeout_seconds) VALUES (?, ?, ?, 'GITHUB', 'github.com', 'acme/web', 'main', "
                + "'0123456789abcdef0123456789abcdef01234567', 'BRANCH', 'PYTEST', "
                + "'python:3.12-slim@sha256:aaaa', '[\"pytest\"]'::jsonb, 'JUNIT_XML', 'SMALL', 'ISOLATED', 600)",
            orgId, runId, connectionId);
    }

    private RepositoryRunProvenance provenance() {
        return new RepositoryRunProvenance("sha256:resolved", 1, 3, 2, 1, 0,
            Instant.now().minusSeconds(40), Instant.now().minusSeconds(35), Instant.now());
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM repository_run WHERE run_id = ?", String.class, runId);
    }

    @Test
    void lifecycleEvents_advanceStatePendingRunningCompleted() {
        lifecycle.onRunStarted(new RunStartedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            executionId, Instant.now(), RunStartedEvent.SCHEMA_VERSION));
        assertThat(state()).isEqualTo("RUNNING");

        lifecycle.onRunCompleted(new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            executionId, Instant.now(), RunCompletedEvent.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
            RunOutcome.FAILED, List.of(new TestCaseSnapshotItem(caseId, "repo case", 0)), List.of()));
        assertThat(state()).isEqualTo("COMPLETED");
    }

    @Test
    void chunkProvenance_fillsCountsDigestAndExitCode() {
        var prov = provenance();
        results.recordChunk(new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            executionId, Instant.now(), ResultChunkEvent.SCHEMA_VERSION, caseId, 0,
            CaseResultSummary.Verdict.FAILED, 42L, "1 failed", List.of(), List.of(), prov));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT runner_image_digest, container_exit_code, items_total, items_passed, items_failed, "
                + "items_skipped, finished_at FROM repository_run WHERE run_id = ?", runId);
        assertThat(row.get("runner_image_digest")).isEqualTo("sha256:resolved");
        assertThat(row.get("container_exit_code")).isEqualTo(1);
        assertThat(row.get("items_total")).isEqualTo(3);
        assertThat(row.get("items_passed")).isEqualTo(2);
        assertThat(row.get("items_failed")).isEqualTo(1);
        assertThat(row.get("finished_at")).isNotNull();
    }

    @Test
    void staleExecutionId_isZeroRowNoOp() {
        results.recordChunk(new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            UUID.randomUUID(), Instant.now(), ResultChunkEvent.SCHEMA_VERSION, caseId, 0,
            CaseResultSummary.Verdict.FAILED, 42L, "x", List.of(), List.of(), provenance()));

        assertThat(jdbc.queryForObject(
            "SELECT runner_image_digest FROM repository_run WHERE run_id = ?", String.class, runId)).isNull();
        assertThat(state()).isEqualTo("PENDING");
    }
}
