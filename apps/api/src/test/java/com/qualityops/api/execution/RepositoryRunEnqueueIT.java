package com.qualityops.api.execution;

import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.ResolvedRepositoryRun;
import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RepositoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ADR-009 §4 — a repository-case suite is admitted only after a successful
 *  preflight: a frozen {@code repository_run} row (40-hex SHA + digest-pinned
 *  image) exists before anything is published; an unresolvable ref rolls the
 *  whole admission back. */
class RepositoryRunEnqueueIT extends AbstractKafkaPostgresIT {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String IMAGE =
        "python:3.12-slim@sha256:3333333333333333333333333333333333333333333333333333333333333333";

    @MockBean private ResolveRepositoryRunUseCase resolveRepositoryRunUseCase;

    @Autowired private EnqueueRunUseCase enqueueRunUseCase;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID projectId;
    private UUID suiteId;
    private UUID environmentId;
    private UUID triggeredBy;
    private UUID connectionId;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        connectionId = jdbc.queryForObject(
            "INSERT INTO repository_connection (org_id, project_id, provider, host, owner_path, repo_name, "
                + "default_ref, created_by) VALUES (?, ?, 'GITHUB', 'github.com', 'acme', 'web', 'main', ?) "
                + "RETURNING id",
            UUID.class, orgId, projectId, triggeredBy);
        jdbc.update("INSERT INTO test_cases (suite_id, org_id, name, order_index, repo_test) "
                + "VALUES (?, ?, 'repo case', 0, ?::jsonb)",
            suiteId, orgId, "{\"repositoryConnectionId\":\"" + connectionId + "\",\"requestedRef\":\"main\","
                + "\"framework\":\"PYTEST\",\"command\":[\"pytest\",\"--junitxml=report.xml\"],"
                + "\"reportFormat\":\"JUNIT_XML\",\"reportPaths\":[\"report.xml\"]}");
    }

    // The queue dispatcher scans run_queue globally; drop this IT's non-terminal
    // rows so sibling execution ITs in the same JVM are not perturbed.
    @AfterEach
    void purgeNonTerminalQueueRows() {
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
    }

    private EnqueueRunCommand cmd() {
        return new EnqueueRunCommand(orgId, projectId, suiteId, environmentId, triggeredBy,
            com.qualityops.api.execution.domain.RunPriority.NORMAL,
            com.qualityops.api.execution.domain.RunSource.MANUAL, null);
    }

    private ResolvedRepositoryRun resolved() {
        var snapshot = new RepoTestSnapshot(connectionId, RepositoryProvider.GITHUB, "github.com",
            "acme/web", "main", SHA, RepoRefType.BRANCH, FrameworkPreset.PYTEST, IMAGE, null,
            List.of("pytest", "--junitxml=report.xml"), RepoReportFormat.JUNIT_XML, List.of("report.xml"),
            List.of(), List.of(), List.of(), null, RepoResourceProfile.SMALL,
            RepoNetworkPolicy.ISOLATED, 600);
        return new ResolvedRepositoryRun(snapshot, RepositoryRunFrozen.fromSnapshot(snapshot));
    }

    @Test
    void enqueue_resolvableRef_stagesFrozenRepositoryRunRowBeforePublish() {
        when(resolveRepositoryRunUseCase.resolve(any())).thenReturn(resolved());

        var result = enqueueRunUseCase.enqueue(cmd());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT commit_sha, runner_image_ref, state, provider, framework_preset "
                + "FROM repository_run WHERE run_id = ? AND org_id = ?", result.runId(), orgId);
        assertThat((String) row.get("commit_sha")).matches("[0-9a-f]{40}");
        assertThat((String) row.get("runner_image_ref")).contains("@sha256:");
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat(row.get("provider")).isEqualTo("GITHUB");
        assertThat(row.get("framework_preset")).isEqualTo("PYTEST");

        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, result.runId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT status FROM test_runs WHERE id = ?",
            String.class, result.runId())).isEqualTo("PENDING");
    }

    @Test
    void enqueue_unresolvableRef_rollsBackWithNoRunOrQueueOrRepositoryRunRow() {
        when(resolveRepositoryRunUseCase.resolve(any()))
            .thenThrow(new RepositoryRefUnresolvableException("no such ref"));

        assertThatThrownBy(() -> enqueueRunUseCase.enqueue(cmd()))
            .isInstanceOf(RepositoryRefUnresolvableException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE org_id = ?",
            Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_queue WHERE org_id = ?",
            Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repository_run WHERE org_id = ?",
            Integer.class, orgId)).isZero();
    }
}
