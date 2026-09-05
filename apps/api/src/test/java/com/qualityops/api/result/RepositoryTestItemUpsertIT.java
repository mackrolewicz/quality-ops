package com.qualityops.api.result;

import com.qualityops.api.result.application.port.in.GenerateResultsUseCase;
import com.qualityops.api.result.application.port.in.RecordCaseResultChunkUseCase;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RepositoryRunProvenance;
import com.qualityops.events.RepositoryTestItem;
import com.qualityops.events.RepositoryTestItem.RepoItemStatus;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.TestCaseSnapshotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §7 — {@code repository_test_item} is epoch-monotone (a lower-epoch
 *  redelivery never overwrites) and the v5 terminal alone reconciles the rows
 *  when every {@code results.chunk} is lost. */
class RepositoryTestItemUpsertIT extends AbstractPostgresIT {

    @Autowired private RecordCaseResultChunkUseCase results;
    @Autowired private GenerateResultsUseCase terminal;
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
                + "VALUES (?, ?, ?, ?, 'RUNNING'::run_status, ?, '{\"cases\":[]}'::jsonb, ?, now()) RETURNING id",
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
                + "'img@sha256:aaaa', '[\"pytest\"]'::jsonb, 'JUNIT_XML', 'SMALL', 'ISOLATED', 600)",
            orgId, runId, connectionId);
    }

    private RepositoryTestItem item(String name, RepoItemStatus status) {
        return new RepositoryTestItem("suite.A", name, status, 10L,
            status == RepoItemStatus.FAILED ? "AssertionError" : null,
            status == RepoItemStatus.FAILED ? "boom" : null);
    }

    private ResultChunkEvent chunk(int epoch, List<RepositoryTestItem> items) {
        return new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), ResultChunkEvent.SCHEMA_VERSION, caseId, epoch, CaseResultSummary.Verdict.FAILED,
            42L, "1 failed", List.of(), items, null);
    }

    private String statusOf(String name) {
        return jdbc.queryForObject(
            "SELECT status FROM repository_test_item WHERE run_id = ? AND name = ?", String.class, runId, name);
    }

    @Test
    void lowerEpochRedelivery_doesNotOverwriteHigherEpochRow() {
        results.recordChunk(chunk(1, List.of(item("t1", RepoItemStatus.PASSED),
            item("t2", RepoItemStatus.FAILED))));
        assertThat(statusOf("t1")).isEqualTo("PASSED");

        // stale epoch-0 redelivery flips both to ERROR — must be ignored
        results.recordChunk(chunk(0, List.of(item("t1", RepoItemStatus.ERROR),
            item("t2", RepoItemStatus.ERROR))));

        assertThat(statusOf("t1")).isEqualTo("PASSED");
        assertThat(statusOf("t2")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
            "SELECT max(attempt_epoch) FROM repository_test_item WHERE run_id = ?", Integer.class, runId))
            .isEqualTo(1);
    }

    @Test
    void everyChunkLost_v5TerminalReconstructsItemsAndProvenance() {
        var prov = new RepositoryRunProvenance("sha256:x", 1, 2, 1, 1, 0,
            Instant.now().minusSeconds(20), Instant.now().minusSeconds(15), Instant.now());
        var cr = new CaseResultSummary(caseId, CaseResultSummary.Verdict.FAILED, 42L, "1 failed", 0,
            List.of(), List.of(item("t1", RepoItemStatus.PASSED), item("t2", RepoItemStatus.FAILED)), prov);
        var event = new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
            RunOutcome.FAILED, List.of(new TestCaseSnapshotItem(caseId, "repo case", 0)), List.of(cr));

        terminal.generateResults(event);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repository_test_item WHERE run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(statusOf("t2")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
            "SELECT items_total FROM repository_run WHERE run_id = ?", Integer.class, runId)).isEqualTo(2);
    }
}
