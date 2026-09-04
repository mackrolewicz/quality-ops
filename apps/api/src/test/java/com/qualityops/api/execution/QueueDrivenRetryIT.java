package com.qualityops.api.execution;

import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.events.RunFailedEvent;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-007 §2 — a whole-execution {@code runs.failed} spawns one budgeted retry. */
@TestPropertySource(properties = "qualityops.scheduling.retry.max-active-per-org=1")
class QueueDrivenRetryIT extends AbstractKafkaPostgresIT {

    @Autowired private EnqueueRunUseCase enqueueRunUseCase;
    @Autowired private QueueDispatchService queueDispatchService;
    @Autowired private ApplyRunLifecycleUseCase applyRunLifecycleUseCase;
    @Autowired private GetRunUseCase getRunUseCase;
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
        ItFixtures.insertCases(jdbc, orgId, suiteId, 2);
    }

    @AfterEach
    void purge() {
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
    }

    private UUID dispatchedRun() {
        var runId = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
        assertThat(queueDispatchService.dispatchAvailable()).isGreaterThanOrEqualTo(1);
        return runId;
    }

    private UUID executionId(UUID runId) {
        return jdbc.queryForObject("SELECT execution_id FROM test_runs WHERE id = ?", UUID.class, runId);
    }

    private RunFailedEvent failed(UUID orgIdArg, UUID runId, String reason) {
        return new RunFailedEvent(UUID.randomUUID(), UUID.randomUUID(), orgIdArg, runId,
            executionId(runId), Instant.now(), RunFailedEvent.SCHEMA_VERSION, reason);
    }

    @Test
    void runsFailed_transientReason_enqueuesFreshRetryRow() {
        var original = dispatchedRun();

        applyRunLifecycleUseCase.onRunFailed(failed(orgId, original, "worker interrupted"));

        var retryRunId = jdbc.queryForObject(
            "SELECT run_id FROM run_queue WHERE retry_of = ?", UUID.class, original);
        assertThat(retryRunId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT retry_count FROM run_queue WHERE run_id = ?",
            Integer.class, retryRunId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, retryRunId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, retryRunId)).isEqualTo("QUEUED");
        String origSnap = jdbc.queryForObject(
            "SELECT config_snapshot::text FROM test_runs WHERE id = ?", String.class, original);
        String retrySnap = jdbc.queryForObject(
            "SELECT config_snapshot::text FROM test_runs WHERE id = ?", String.class, retryRunId);
        assertThat(retrySnap).isEqualTo(origSnap);

        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, original)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, original)).isEqualTo("FAILED");

        queueDispatchService.dispatchAvailable();
        assertRecordPresent("runs.requested", retryRunId, Duration.ofSeconds(5));
    }

    @Test
    void runsFailed_redelivered_doesNotEnqueueSecondRetry() {
        var original = dispatchedRun();

        applyRunLifecycleUseCase.onRunFailed(failed(orgId, original, "worker interrupted"));
        applyRunLifecycleUseCase.onRunFailed(failed(orgId, original, "worker interrupted"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_queue WHERE retry_of = ?",
            Integer.class, original)).isEqualTo(1);
    }

    @Test
    void runsFailed_nonRetryableReason_noRetry() {
        var original = dispatchedRun();
        double before = meterRegistry.find("qualityops.queue.retries")
            .tag("outcome", "not_retryable").counter().count();

        applyRunLifecycleUseCase.onRunFailed(failed(orgId, original, "execution cancelled before start"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_queue WHERE retry_of = ?",
            Integer.class, original)).isZero();
        assertThat(meterRegistry.find("qualityops.queue.retries").tag("outcome", "not_retryable")
            .counter().count()).isEqualTo(before + 1);
    }

    @Test
    void runsFailed_perRunBudgetExhausted_noRetry() {
        var original = dispatchedRun();
        jdbc.update("UPDATE run_queue SET retry_count = 2 WHERE run_id = ?", original);

        applyRunLifecycleUseCase.onRunFailed(failed(orgId, original, "worker interrupted"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_queue WHERE retry_of = ?",
            Integer.class, original)).isZero();
    }

    @Test
    void runsFailed_perOrgWindowFull_noRetry() {
        var first = dispatchedRun();
        applyRunLifecycleUseCase.onRunFailed(failed(orgId, first, "worker interrupted")); // fills the window (max 1)
        var second = dispatchedRun();

        applyRunLifecycleUseCase.onRunFailed(failed(orgId, second, "worker interrupted"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_queue WHERE retry_of = ?",
            Integer.class, second)).isZero();
    }

    @Test
    void runsFailed_foreignOrg_noRetry_noMove() {
        var original = dispatchedRun();
        var otherOrg = ItFixtures.insertOrg(jdbc);

        applyRunLifecycleUseCase.onRunFailed(failed(otherOrg, original, "worker interrupted"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_queue WHERE retry_of = ?",
            Integer.class, original)).isZero();
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, original)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, original)).isEqualTo("DISPATCHED");
    }

    @Test
    void runResponse_forRetryRun_carriesRetryOfAndCount() {
        var original = dispatchedRun();
        applyRunLifecycleUseCase.onRunFailed(failed(orgId, original, "worker interrupted"));
        var retryRunId = jdbc.queryForObject(
            "SELECT run_id FROM run_queue WHERE retry_of = ?", UUID.class, original);

        var response = getRunUseCase.get(retryRunId, orgId);

        assertThat(response.retryOf()).isEqualTo(original);
        assertThat(response.retryCount()).isEqualTo(1);
    }

    private void assertRecordPresent(String topic, UUID runId, Duration window) {
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "retry-it-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            var deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(300)).records(topic)) {
                    if (runId.toString().equals(record.key())) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("Expected a runs.requested record for retry run " + runId);
    }
}
