package com.qualityops.api.execution;

import com.qualityops.api.execution.application.port.in.CancelRunUseCase;
import com.qualityops.api.execution.application.port.in.CancelRunUseCase.Outcome;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
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
 * Hard invariant (ADR-006 §5.2): a run cancelled while still QUEUED never
 * produces a {@code runs.requested} — the API-only path, no Kafka. After the
 * cancel the dispatcher must skip it ({@code WHERE queue_state='QUEUED'}).
 */
class RunCancellationIT extends AbstractKafkaPostgresIT {

    private static final String RUNS_REQUESTED_TOPIC = "runs.requested";

    @Autowired private EnqueueRunUseCase enqueueRunUseCase;
    @Autowired private CancelRunUseCase cancelRunUseCase;
    @Autowired private QueueDispatchService queueDispatchService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker broker;

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

    // The dispatcher scans run_queue globally; a foreign-org cancel deliberately
    // leaves a QUEUED row — drop non-terminal rows so later execution ITs are clean.
    @AfterEach
    void purgeNonTerminalQueueRows() {
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
    }

    @Test
    void cancelWhileQueued_neverPublishesRunRequested_andMarksBothCancelled() {
        var result = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null));
        var runId = result.runId();

        var cancel = cancelRunUseCase.cancel(runId, orgId);
        assertThat(cancel.outcome()).isEqualTo(Outcome.CANCELLED_QUEUED);

        int dispatched = queueDispatchService.dispatchAvailable();

        assertThat(dispatched).isZero();
        assertNoRecord(RUNS_REQUESTED_TOPIC, runId, Duration.ofSeconds(3));
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelAfterDispatcherClaim_returns202Cooperative_notNotCancellable() {
        var result = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null));
        var runId = result.runId();

        int dispatched = queueDispatchService.dispatchAvailable();
        assertThat(dispatched).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, runId)).isEqualTo("DISPATCHED");

        var cancel = cancelRunUseCase.cancel(runId, orgId);

        assertThat(cancel.outcome()).isEqualTo(Outcome.CANCEL_REQUESTED);
        assertThat(jdbc.queryForObject("SELECT cancel_requested FROM run_queue WHERE run_id = ?",
            Boolean.class, runId)).isTrue();
        assertRecordPresent("runs.cancel", runId, Duration.ofSeconds(5));
    }

    @Test
    void cancelWhileQueued_repositoryRun_marksRepositoryRunCancelled_noKafka() {
        var result = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null));
        var runId = result.runId();
        // Simulate a staged repository_run row for this (QUEUED) run.
        var connectionId = jdbc.queryForObject(
            "INSERT INTO repository_connection (org_id, project_id, provider, host, owner_path, repo_name, "
                + "default_ref, created_by) VALUES (?, ?, 'GITHUB', 'github.com', 'acme', 'web', 'main', ?) "
                + "RETURNING id",
            UUID.class, orgId, projectId, triggeredBy);
        jdbc.update("INSERT INTO repository_run (org_id, run_id, repository_connection_id, provider, "
                + "repo_host, repo_path, requested_ref, commit_sha, ref_type, framework_preset, "
                + "runner_image_ref, command_json, report_format, resource_profile, network_policy, "
                + "timeout_seconds) VALUES (?, ?, ?, 'GITHUB', 'github.com', 'acme/web', 'main', "
                + "'0123456789abcdef0123456789abcdef01234567', 'BRANCH', 'PYTEST', 'img@sha256:aaaa', "
                + "'[\"pytest\"]'::jsonb, 'JUNIT_XML', 'SMALL', 'ISOLATED', 600)",
            orgId, runId, connectionId);

        var cancel = cancelRunUseCase.cancel(runId, orgId);

        assertThat(cancel.outcome()).isEqualTo(Outcome.CANCELLED_QUEUED);
        assertNoRecord(RUNS_REQUESTED_TOPIC, runId, Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT state FROM repository_run WHERE run_id = ?",
            String.class, runId)).isEqualTo("CANCELLED");
    }

    @Test
    void cancel_foreignOrg_returnsNotCancellable() {
        var result = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null));
        var runId = result.runId();
        var otherOrg = ItFixtures.insertOrg(jdbc);

        var cancel = cancelRunUseCase.cancel(runId, otherOrg);

        assertThat(cancel.outcome()).isEqualTo(Outcome.NOT_CANCELLABLE);
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id = ?",
            String.class, runId)).isEqualTo("PENDING");
    }

    private void assertRecordPresent(String topic, UUID runId, Duration window) {
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "cancel-it-present-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            var deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(300));
                for (var record : records.records(topic)) {
                    if (runId.toString().equals(record.key())) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("Expected a RunCancelRequestedEvent for run " + runId + " on " + topic);
    }

    private void assertNoRecord(String topic, UUID runId, Duration window) {
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "cancel-it-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            var deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(300));
                for (var record : records.records(topic)) {
                    if (runId.toString().equals(record.key())) {
                        throw new AssertionError("QUEUED-cancelled run appeared on " + topic);
                    }
                }
            }
        }
    }
}
