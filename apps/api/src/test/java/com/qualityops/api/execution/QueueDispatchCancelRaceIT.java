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

/**
 * B2 (ADR-006 amendment §3): the send is lost while a cooperative cancel is
 * already recorded on the DISPATCHED row. rollbackDispatch flips the row to
 * CANCELLED (its {@code cancel_requested} branch), and the dispatcher then
 * reconciles {@code test_runs} PENDING -> CANCELLED and nulls the frozen event.
 *
 * <p>Extends {@link AbstractPostgresIT} (not the Kafka base): {@code RunEventPublisher}
 * is mocked, so no broker is exercised.
 */
class QueueDispatchCancelRaceIT extends AbstractPostgresIT {

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

    @Test
    void dispatchAvailable_sendLostWhileCancelRequested_reconcilesRunToCancelled() {
        var runId = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
        jdbc.update("UPDATE run_queue SET cancel_requested = true WHERE run_id = ?", runId);
        doThrow(new RunEventPublishException("boom", new RuntimeException()))
            .when(publisher).publishRunRequested(ArgumentMatchers.any());

        queueDispatchService.dispatchAvailable();

        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
            "SELECT requested_event_json IS NULL FROM run_queue WHERE run_id = ?",
            Boolean.class, runId)).isTrue();
    }
}
