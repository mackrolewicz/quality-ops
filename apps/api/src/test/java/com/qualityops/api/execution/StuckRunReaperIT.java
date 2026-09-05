package com.qualityops.api.execution;

import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import com.qualityops.api.execution.application.service.StuckRunReaperService;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-007 §1 — the reaper closes the stranded-DISPATCHED and stuck-active gaps.
 * Every write is org-scoped; foreign-org rows are untouched.
 */
class StuckRunReaperIT extends AbstractKafkaPostgresIT {

    @Autowired private EnqueueRunUseCase enqueueRunUseCase;
    @Autowired private QueueDispatchService queueDispatchService;
    @Autowired private StuckRunReaperService reaperService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private MeterRegistry meterRegistry;

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
        ItFixtures.insertCases(jdbc, orgId, suiteId, 1);
    }

    @AfterEach
    void purge() {
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
    }

    private UUID enqueue() {
        return enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
    }

    private UUID dispatchOne() {
        var runId = enqueue();
        assertThat(queueDispatchService.dispatchAvailable()).isGreaterThanOrEqualTo(1);
        return runId;
    }

    @Test
    void strandedDispatched_olderThanGrace_republishesRunsRequested() {
        var runId = dispatchOne();
        jdbc.update("UPDATE run_queue SET dispatched_at = now() - interval '3 minutes' WHERE run_id = ?", runId);

        // foreign-org stranded row still inside grace — must be untouched
        var otherOrg = ItFixtures.insertOrg(jdbc);
        var otherProject = ItFixtures.insertProject(jdbc, otherOrg);
        var otherSuite = ItFixtures.insertSuite(jdbc, otherOrg, otherProject);
        var otherEnv = ItFixtures.insertEnvironment(jdbc, otherOrg, otherProject);
        var otherUser = ItFixtures.insertUser(jdbc, otherOrg);
        ItFixtures.insertCases(jdbc, otherOrg, otherSuite, 1);
        var foreignRun = enqueueRunUseCase.enqueue(new EnqueueRunCommand(otherOrg, otherProject,
            otherSuite, otherEnv, otherUser, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
        queueDispatchService.dispatchAvailable();

        reaperService.sweep();

        assertRecordPresent("runs.requested", runId, Duration.ofSeconds(5));
        assertThat(jdbc.queryForObject("SELECT dispatch_attempts FROM run_queue WHERE run_id = ?",
            Integer.class, runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, foreignRun)).isEqualTo("DISPATCHED");
        assertThat(jdbc.queryForObject("SELECT dispatch_attempts FROM run_queue WHERE run_id = ?",
            Integer.class, foreignRun)).isEqualTo(1);
    }

    @Test
    void strandedDispatched_cancelRacedInWindow_reconcilesToCancelled() {
        var runId = dispatchOne();
        jdbc.update("UPDATE run_queue SET dispatched_at = now() - interval '3 minutes', "
            + "cancel_requested = true WHERE run_id = ?", runId);

        reaperService.sweep();

        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
        assertThat(meterRegistry.find("qualityops.queue.reaped").tag("kind", "cancel_reconciled")
            .counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void stuckRunning_pastRunTimeout_failsBothTablesNoKafka() {
        var runId = dispatchOne();
        jdbc.update("UPDATE test_runs SET status = 'RUNNING', started_at = now() - interval '40 minutes' "
            + "WHERE id = ?", runId);
        jdbc.update("UPDATE run_queue SET queue_state = 'RUNNING', "
            + "dispatched_at = now() - interval '40 minutes' WHERE run_id = ?", runId);

        // foreign-org RUNNING row, fresh — untouched
        var otherOrg = ItFixtures.insertOrg(jdbc);
        var otherProject = ItFixtures.insertProject(jdbc, otherOrg);
        var otherSuite = ItFixtures.insertSuite(jdbc, otherOrg, otherProject);
        var otherEnv = ItFixtures.insertEnvironment(jdbc, otherOrg, otherProject);
        var otherUser = ItFixtures.insertUser(jdbc, otherOrg);
        ItFixtures.insertCases(jdbc, otherOrg, otherSuite, 1);
        var foreignRun = enqueueRunUseCase.enqueue(new EnqueueRunCommand(otherOrg, otherProject,
            otherSuite, otherEnv, otherUser, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
        queueDispatchService.dispatchAvailable();
        jdbc.update("UPDATE test_runs SET status = 'RUNNING', started_at = now() WHERE id = ?", foreignRun);
        jdbc.update("UPDATE run_queue SET queue_state = 'RUNNING' WHERE run_id = ?", foreignRun);

        reaperService.sweep();

        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, runId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM test_runs WHERE id = ?",
            Boolean.class, runId)).isTrue();
        assertNoRecord("runs.completed", runId, Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, foreignRun)).isEqualTo("RUNNING");
    }

    @Test
    void stuckRun_realTerminalRaces_reaperIsNoop() {
        var runId = dispatchOne();
        jdbc.update("UPDATE test_runs SET status = 'PASSED', started_at = now() - interval '40 minutes', "
            + "completed_at = now() WHERE id = ?", runId);
        jdbc.update("UPDATE run_queue SET queue_state = 'COMPLETED', terminal_at = now() WHERE run_id = ?", runId);

        reaperService.sweep();

        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("PASSED");
    }

    @Test
    void sweep_runTwice_isIdempotent() {
        var runId = dispatchOne();
        jdbc.update("UPDATE test_runs SET status = 'RUNNING', started_at = now() - interval '40 minutes' "
            + "WHERE id = ?", runId);
        jdbc.update("UPDATE run_queue SET queue_state = 'RUNNING' WHERE run_id = ?", runId);

        reaperService.sweep();
        double afterFirst = meterRegistry.find("qualityops.queue.reaped").tag("kind", "stuck_failed")
            .counter().count();
        reaperService.sweep();
        double afterSecond = meterRegistry.find("qualityops.queue.reaped").tag("kind", "stuck_failed")
            .counter().count();

        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("FAILED");
    }

    // ---- helpers (copied from RunCancellationIT) ----

    private void assertRecordPresent(String topic, UUID runId, Duration window) {
        try (Consumer<String, String> consumer = stringConsumer(topic)) {
            var deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(300)).records(topic)) {
                    if (runId.toString().equals(record.key())) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("Expected a record for run " + runId + " on " + topic);
    }

    private void assertNoRecord(String topic, UUID runId, Duration window) {
        try (Consumer<String, String> consumer = stringConsumer(topic)) {
            var deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(300)).records(topic)) {
                    if (runId.toString().equals(record.key())) {
                        throw new AssertionError("Unexpected record for run " + runId + " on " + topic);
                    }
                }
            }
        }
    }

    private Consumer<String, String> stringConsumer(String topic) {
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "reaper-it-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var consumer = new KafkaConsumer<String, String>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
