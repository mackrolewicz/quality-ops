package com.qualityops.api.execution;

import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.execution.exception.RunEventPublishException;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B1 (ADR-006 amendment §3): when the dispatcher abandons a claim — corrupt
 * frozen event, or the send failing at {@code dispatch-max-attempts} — it now
 * reconciles {@code test_runs} PENDING -> FAILED synchronously and nulls
 * {@code requested_event_json}. The reconciliation UPDATE is org-scoped, so a
 * foreign tenant's QUEUED run is never touched.
 *
 * <p>Extends {@link AbstractPostgresIT} (not the Kafka base): {@code RunEventPublisher}
 * is mocked, so no broker is exercised and no @EmbeddedKafka context is needed.
 * The ceiling case is reached by pre-ageing {@code dispatch_attempts} rather than
 * a per-class {@code dispatch-max-attempts} property override.
 */
class QueueDispatchFailureIT extends AbstractPostgresIT {

    @MockBean private RunEventPublisher publisher;

    @Autowired private EnqueueRunUseCase enqueueRunUseCase;
    @Autowired private QueueDispatchService queueDispatchService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID projectId;
    private UUID suiteId;
    private UUID environmentId;
    private UUID triggeredBy;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        ItFixtures.insertCases(jdbc, orgId, suiteId, 2);
    }

    // The dispatcher scans run_queue globally; drop any non-terminal rows this
    // class left behind so later execution ITs see a clean queue.
    @AfterEach
    void purgeNonTerminalQueueRows() {
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
    }

    private UUID enqueue(UUID org, UUID project, UUID suite, UUID env, UUID user) {
        return enqueueRunUseCase.enqueue(new EnqueueRunCommand(org, project, suite, env, user,
            RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
    }

    /** Pre-age the row so the very next dispatch attempt hits the default ceiling (5). */
    private void atCeiling(UUID runId) {
        jdbc.update("UPDATE run_queue SET dispatch_attempts = 4 WHERE run_id = ?", runId);
    }

    private String queueState(UUID runId) {
        return jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?", String.class, runId);
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?", String.class, runId);
    }

    private boolean frozenEventIsNull(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT requested_event_json IS NULL FROM run_queue WHERE run_id = ?", Boolean.class, runId));
    }

    @Test
    void dispatchAvailable_publishThrowsAtCeiling_reconcilesRunToFailed() {
        doThrow(new RunEventPublishException("boom", new RuntimeException()))
            .when(publisher).publishRunRequested(ArgumentMatchers.any());
        var runId = enqueue(orgId, projectId, suiteId, environmentId, triggeredBy);
        atCeiling(runId);

        queueDispatchService.dispatchAvailable();

        assertThat(queueState(runId)).isEqualTo("FAILED");
        assertThat(runStatus(runId)).isEqualTo("FAILED");
        assertThat(frozenEventIsNull(runId)).isTrue();
    }

    @Test
    void dispatchAvailable_corruptFrozenEvent_reconcilesRunToFailed() {
        var runId = enqueue(orgId, projectId, suiteId, environmentId, triggeredBy);
        jdbc.update("UPDATE run_queue SET requested_event_json = '[]'::jsonb WHERE run_id = ?", runId);

        queueDispatchService.dispatchAvailable();

        assertThat(queueState(runId)).isEqualTo("FAILED");
        assertThat(runStatus(runId)).isEqualTo("FAILED");
        assertThat(frozenEventIsNull(runId)).isTrue();
        // dispatchAvailable() scans run_queue globally, not just this test's own
        // row — in the full apps/api IT suite it may legitimately also dispatch
        // an unrelated QUEUED row left behind by another class's test (every IT
        // in this module shares one Postgres Testcontainers instance). The
        // assertion that matters is that THIS test's corrupted-event run was
        // never published, not that the mock saw zero interactions at all.
        verify(publisher, never()).publishRunRequested(
            ArgumentMatchers.argThat(e -> e.runId().equals(runId)));
    }

    @Test
    void dispatchAvailable_foreignOrgQueuedRun_isUntouched() {
        doThrow(new RunEventPublishException("boom", new RuntimeException()))
            .when(publisher).publishRunRequested(ArgumentMatchers.any());
        var runA = enqueue(orgId, projectId, suiteId, environmentId, triggeredBy);
        atCeiling(runA);

        queueDispatchService.dispatchAvailable();
        assertThat(queueState(runA)).isEqualTo("FAILED");
        assertThat(runStatus(runA)).isEqualTo("FAILED");

        // A second tenant's run enqueued afterwards must be wholly untouched by
        // the org-scoped PENDING->FAILED reconciliation that fired for orgA.
        var orgB = ItFixtures.insertOrg(jdbc);
        var projectB = ItFixtures.insertProject(jdbc, orgB);
        var suiteB = ItFixtures.insertSuite(jdbc, orgB, projectB);
        var envB = ItFixtures.insertEnvironment(jdbc, orgB, projectB);
        var userB = ItFixtures.insertUser(jdbc, orgB);
        ItFixtures.insertCases(jdbc, orgB, suiteB, 2);
        var runB = enqueue(orgB, projectB, suiteB, envB, userB);

        assertThat(queueState(runB)).isEqualTo("QUEUED");
        assertThat(runStatus(runB)).isEqualTo("PENDING");
    }
}
