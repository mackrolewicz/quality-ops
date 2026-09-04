package com.qualityops.api.execution;

import com.qualityops.api.execution.application.port.in.TriggerRunUseCase;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.result.application.port.out.TestResultRepository;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.RunStartedEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check of the Phase 2A execution flow from the API side: a trigger
 * publishes exactly one {@code runs.requested} and the API does NOT consume it;
 * Worker lifecycle events drive the run through its status machine and generate
 * one result row per snapshot case; cross-topic reorder and at-least-once
 * redelivery produce no duplicate side effects. Multi-tenancy is asserted on the
 * generated {@code test_results} rows.
 */
class RunOrchestrationKafkaIT extends AbstractKafkaPostgresIT {

    private static final String RUNS_REQUESTED_TOPIC = "runs.requested";
    private static final String RUNS_STARTED_TOPIC = "runs.started";
    private static final String RUNS_COMPLETED_TOPIC = "runs.completed";
    private static final String RUNS_FAILED_TOPIC = "runs.failed";
    private static final int SNAPSHOT_SIZE = 4;

    @Autowired private TriggerRunUseCase triggerRunUseCase;
    @Autowired private QueueDispatchService queueDispatchService;
    @Autowired private RunRepository runRepository;
    @Autowired private TestResultRepository testResultRepository;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmbeddedKafkaBroker broker;

    private UUID orgId;
    private UUID projectId;
    private UUID suiteId;
    private UUID environmentId;
    private UUID triggeredBy;
    private List<UUID> caseIds;
    private UUID lastExecutionId;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        caseIds = ItFixtures.insertCases(jdbc, orgId, suiteId, SNAPSHOT_SIZE);
    }

    // The dispatcher scans run_queue globally; drop any non-terminal rows this
    // class left behind so a later execution IT's queue assertions stay clean.
    @AfterEach
    void purgeNonTerminalQueueRows() {
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
    }

    @Test
    void postRunTrigger_enqueuesQueuedRow_andPublishesNothingUntilDispatch() {
        var runId = triggerRunUseCase.trigger(
            new CreateRunRequest(projectId, suiteId, environmentId, null), orgId, triggeredBy).id();

        // enqueue only: run_queue QUEUED, test_runs PENDING, NOTHING on runs.requested
        assertThat(queueState(runId)).isEqualTo("QUEUED");
        assertThat(currentStatus(runId)).isEqualTo(RunStatus.PENDING);
        assertNoRecord(RUNS_REQUESTED_TOPIC, runId, Duration.ofSeconds(3));

        // dispatch publishes exactly one runs.requested and flips the row to DISPATCHED
        int n = queueDispatchService.dispatchAvailable();
        assertThat(n).isEqualTo(1);
        var payload = awaitFirstValue(RUNS_REQUESTED_TOPIC, runId);
        caseIds.forEach(id -> assertThat(payload).contains(id.toString()));
        assertThat(payload).contains("\"schemaVersion\":5");
        assertThat(queueState(runId)).isEqualTo("DISPATCHED");

        // the API has no runs.requested listener -> run stays PENDING until runs.started
        await().during(2, SECONDS).atMost(6, SECONDS).untilAsserted(() ->
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.PENDING));
    }

    private String queueState(UUID runId) {
        return jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id = ?", String.class, runId);
    }

    private void assertNoRecord(String topic, UUID runId, Duration window) {
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "it-noverify-" + UUID.randomUUID(), "true");
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
                        throw new AssertionError("Unexpected record on " + topic + " for run " + runId);
                    }
                }
            }
        }
    }

    @Test
    void runStartedThenRunCompleted_drivesPendingToRunningToPassedWithResults() {
        var runId = persistPendingRun();

        kafkaTemplate.send(RUNS_STARTED_TOPIC, runId.toString(), startedEvent(runId));
        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.RUNNING));

        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, runId.toString(), completedEvent(runId, RunOutcome.PASSED));
        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.PASSED));
        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(resultCount(runId)).isEqualTo((long) SNAPSHOT_SIZE));
        assertThat(distinctResultOrgIds(runId)).containsExactly(orgId);
    }

    @Test
    void runFailedEvent_afterStarted_setsRunFailed_andGeneratesNoResults() {
        var runId = persistPendingRun();

        kafkaTemplate.send(RUNS_STARTED_TOPIC, runId.toString(), startedEvent(runId));
        kafkaTemplate.send(RUNS_FAILED_TOPIC, runId.toString(), failedEvent(runId));

        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.FAILED));
        await().during(3, SECONDS).atMost(8, SECONDS).untilAsserted(() ->
            assertThat(resultCount(runId)).isZero());
    }

    @Test
    void runCompletedBeforeRunStarted_stillTransitionsAndGeneratesResults() {
        var runId = persistPendingRun();

        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, runId.toString(), completedEvent(runId, RunOutcome.PASSED));

        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.PASSED));
        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(resultCount(runId)).isEqualTo((long) SNAPSHOT_SIZE));
    }

    @Test
    void duplicateRunCompletedDelivery_generatesResultsOnce() {
        var runId = persistPendingRun();
        var event = completedEvent(runId, RunOutcome.PASSED);

        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, runId.toString(), event);
        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, runId.toString(), event);

        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(resultCount(runId)).isEqualTo((long) SNAPSHOT_SIZE));
        await().during(3, SECONDS).atMost(8, SECONDS).untilAsserted(() ->
            assertThat(resultCount(runId)).isEqualTo((long) SNAPSHOT_SIZE));
    }

    @Test
    void duplicateRunStartedDelivery_noDoubleTransition() {
        var runId = persistPendingRun();
        var event = startedEvent(runId);

        kafkaTemplate.send(RUNS_STARTED_TOPIC, runId.toString(), event);
        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.RUNNING));
        var startedAt = readStartedAt(runId);

        kafkaTemplate.send(RUNS_STARTED_TOPIC, runId.toString(), event);
        await().during(3, SECONDS).atMost(8, SECONDS).untilAsserted(() -> {
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.RUNNING);
            assertThat(readStartedAt(runId)).isEqualTo(startedAt);
        });
    }

    @Test
    void runCompletedWithForeignOrgId_leavesRunPending_andGeneratesNoResultsForAnyTenant() {
        var runId = persistPendingRun();
        var foreignOrgId = ItFixtures.insertOrg(jdbc);

        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, runId.toString(),
            completedEvent(runId, foreignOrgId, RunOutcome.PASSED));

        // The org-scoped lifecycle UPDATE must no-op, AND the result path's
        // run-ownership guard must reject the cross-tenant event: zero rows for
        // the owning tenant and zero for the forger's org.
        await().during(3, SECONDS).atMost(8, SECONDS).untilAsserted(() -> {
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.PENDING);
            assertThat(resultCount(runId)).isZero();
            assertThat(rawResultRowCount(runId)).isZero();
            assertThat(rawResultRowCount(runId, foreignOrgId)).isZero();
        });
    }

    @Test
    void runCompletedWithStaleExecutionId_leavesRunPending_andGeneratesNoResults() {
        var runId = persistPendingRun();
        var stale = new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            UUID.randomUUID() /* wrong executionId */, Instant.now(), RunCompletedEvent.SCHEMA_VERSION,
            projectId, suiteId, RunOutcome.PASSED, wireSnapshot(), null);
        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, runId.toString(), stale);
        await().during(3, SECONDS).atMost(8, SECONDS).untilAsserted(() -> {
            assertThat(currentStatus(runId)).isEqualTo(RunStatus.PENDING);
            assertThat(resultCount(runId)).isZero();
            assertThat(rawResultRowCount(runId)).isZero();
        });
    }

    @Test
    void runCompletedV2WithCaseResults_generatesRealPerCaseRows() {
        var runId = persistPendingRun();
        var summaries = new ArrayList<CaseResultSummary>();
        for (int i = 0; i < caseIds.size(); i++) {
            var v = i == 0 ? CaseResultSummary.Verdict.FAILED : CaseResultSummary.Verdict.PASSED;
            summaries.add(new CaseResultSummary(caseIds.get(i), v, 100L + i, i == 0 ? "boom" : null));
        }
        var event = new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            lastExecutionId, Instant.now(), RunCompletedEvent.SCHEMA_VERSION,
            projectId, suiteId, RunOutcome.FAILED, wireSnapshot(), List.copyOf(summaries));
        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, runId.toString(), event);

        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(resultCount(runId)).isEqualTo((long) SNAPSHOT_SIZE));
        var failed = jdbc.queryForObject(
            "SELECT count(*) FROM test_results WHERE run_id=? AND status='FAILED'::result_status", Long.class, runId);
        assertThat(failed).isEqualTo(1L);
        var maxDur = jdbc.queryForObject(
            "SELECT max(duration_ms) FROM test_results WHERE run_id=?", Integer.class, runId);
        assertThat(maxDur).isEqualTo(100 + SNAPSHOT_SIZE - 1);
    }

    // ---- helpers ----

    private UUID persistPendingRun() {
        var executionId = UUID.randomUUID();
        var run = new TestRun(UUID.randomUUID(), orgId, projectId, suiteId, environmentId,
            executionId, RunStatus.PENDING, triggeredBy, new RunConfigSnapshot(domainSnapshot()),
            null, null, Instant.now());
        var saved = runRepository.save(run);
        lastExecutionId = executionId;
        return saved.id();
    }

    private List<TestCaseSnapshotItem> domainSnapshot() {
        var items = new ArrayList<TestCaseSnapshotItem>();
        for (int i = 0; i < caseIds.size(); i++) {
            items.add(new TestCaseSnapshotItem(caseIds.get(i), "Case " + i, i));
        }
        return List.copyOf(items);
    }

    private List<com.qualityops.events.TestCaseSnapshotItem> wireSnapshot() {
        var items = new ArrayList<com.qualityops.events.TestCaseSnapshotItem>();
        for (int i = 0; i < caseIds.size(); i++) {
            items.add(new com.qualityops.events.TestCaseSnapshotItem(caseIds.get(i), "Case " + i, i));
        }
        return List.copyOf(items);
    }

    private RunStartedEvent startedEvent(UUID runId) {
        return new RunStartedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, lastExecutionId,
            Instant.now(), RunStartedEvent.SCHEMA_VERSION);
    }

    private RunCompletedEvent completedEvent(UUID runId, RunOutcome outcome) {
        return completedEvent(runId, orgId, outcome);
    }

    private RunCompletedEvent completedEvent(UUID runId, UUID eventOrgId, RunOutcome outcome) {
        return new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), eventOrgId, runId, lastExecutionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, projectId, suiteId, outcome, wireSnapshot(), null);
    }

    private RunFailedEvent failedEvent(UUID runId) {
        return new RunFailedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, lastExecutionId,
            Instant.now(), RunFailedEvent.SCHEMA_VERSION, "execution error");
    }

    private RunStatus currentStatus(UUID runId) {
        return runRepository.findByIdAndOrgId(runId, orgId).orElseThrow().status();
    }

    private long resultCount(UUID runId) {
        return testResultRepository.findAllByRunIdAndOrgId(runId, orgId, 1, 100).total();
    }

    private List<UUID> distinctResultOrgIds(UUID runId) {
        return jdbc.queryForList("SELECT DISTINCT org_id FROM test_results WHERE run_id = ?", UUID.class, runId);
    }

    private long rawResultRowCount(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM test_results WHERE run_id = ?", Long.class, runId);
    }

    private long rawResultRowCount(UUID runId, UUID resultOrgId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM test_results WHERE run_id = ? AND org_id = ?", Long.class, runId, resultOrgId);
    }

    private Instant readStartedAt(UUID runId) {
        return jdbc.queryForObject("SELECT started_at FROM test_runs WHERE id = ?", Instant.class, runId);
    }

    private String awaitFirstValue(String topic, UUID runId) {
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "it-verify-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            var deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records.records(topic)) {
                    if (runId.toString().equals(record.key())) {
                        return record.value();
                    }
                }
            }
        }
        throw new AssertionError("No record on " + topic + " for run " + runId);
    }
}
