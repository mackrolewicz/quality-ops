package com.qualityops.worker;

import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.support.AbstractWorkerKafkaPostgresIT;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

class WorkerOrchestrationIT extends AbstractWorkerKafkaPostgresIT {

    private static final String REQUESTED = "runs.requested";
    private static final String STARTED = "runs.started";
    private static final String COMPLETED = "runs.completed";
    private static final String FAILED = "runs.failed";
    private static final int SNAPSHOT_SIZE = 4;
    private static final int PASS_DRAW = 10;   // < 80 → PASSED
    private static final int FAIL_DRAW = 85;   // >= 80 → FAILED (still a completed execution)

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;

    // Deterministic outcome control: the simulation draws random.nextInt(100).
    @MockBean private RandomGenerator random;

    private Consumer<String, String> observer;

    @BeforeEach
    void subscribe() {
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "it-observer-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(props);
        observer.subscribe(List.of(STARTED, COMPLETED, FAILED));
    }

    @AfterEach
    void close() {
        observer.close();
    }

    @Test
    void runRequested_passedOutcome_publishesOneStartedAndOneCompletedWithEchoedEnvelope() {
        when(random.nextInt(100)).thenReturn(PASS_DRAW);
        var runId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var snapshot = snapshot();

        kafkaTemplate.send(REQUESTED, runId.toString(),
            requested(runId, orgId, correlationId, executionId, snapshot));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(20, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
            assertThat(count(seen, FAILED, runId)).isZero();
        });

        var terminal = firstValue(seen, COMPLETED, runId);
        assertThat(terminal).isNotNull();
        assertThat(terminal).contains("\"runId\":\"" + runId + "\"");
        assertThat(terminal).contains("\"orgId\":\"" + orgId + "\"");
        assertThat(terminal).contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(terminal).contains("\"executionId\":\"" + executionId + "\"");
        assertThat(terminal).contains("\"outcome\":\"PASSED\"");
        snapshot.forEach(item ->
            assertThat(terminal).contains("\"testCaseId\":\"" + item.testCaseId() + "\""));
    }

    @Test
    void runRequested_failedOutcome_publishesOneCompletedAndZeroFailed() {
        when(random.nextInt(100)).thenReturn(FAIL_DRAW);
        var runId = UUID.randomUUID();

        kafkaTemplate.send(REQUESTED, runId.toString(),
            requested(runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), snapshot()));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(20, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        // A FAILED test outcome is still a *completed* execution — no runs.failed.
        await().during(3, SECONDS).atMost(9, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, FAILED, runId)).isZero();
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        assertThat(firstValue(seen, COMPLETED, runId)).contains("\"outcome\":\"FAILED\"");
    }

    @Test
    void runRequestedSameEventDeliveredTwice_stillOneStartedOneTerminal_noExtraLifecycleEvents() {
        when(random.nextInt(100)).thenReturn(PASS_DRAW);
        var runId = UUID.randomUUID();
        var event = requested(runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), snapshot());

        kafkaTemplate.send(REQUESTED, runId.toString(), event);
        kafkaTemplate.send(REQUESTED, runId.toString(), event);

        // Backed by the durable execution_attempt ledger (ADR-003 §3): first delivery
        // CLAIMs and executes; a redelivery observes AlreadyCompleted and re-emits the
        // cached terminal verbatim. Net: exactly one runs.started, and runs.completed is
        // 1 (no redelivery raced) or 2 (original + one idempotent re-emit).
        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(20, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, COMPLETED, runId)).isBetween(1L, 2L);
        });
        await().during(3, SECONDS).atMost(9, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, COMPLETED, runId)).isBetween(1L, 2L);
            assertThat(count(seen, FAILED, runId)).isZero();
        });
    }

    private void drain(List<ConsumerRecord<String, String>> sink) {
        observer.poll(Duration.ofMillis(300)).forEach(sink::add);
    }

    private long count(List<ConsumerRecord<String, String>> records, String topic, UUID runId) {
        return records.stream()
            .filter(r -> r.topic().equals(topic) && runId.toString().equals(r.key()))
            .count();
    }

    private String firstValue(List<ConsumerRecord<String, String>> records, String topic, UUID runId) {
        return records.stream()
            .filter(r -> r.topic().equals(topic) && runId.toString().equals(r.key()))
            .map(ConsumerRecord::value)
            .findFirst().orElse(null);
    }

    private List<TestCaseSnapshotItem> snapshot() {
        var items = new ArrayList<TestCaseSnapshotItem>();
        for (int i = 0; i < SNAPSHOT_SIZE; i++) {
            items.add(new TestCaseSnapshotItem(UUID.randomUUID(), "Case " + i, i));
        }
        return List.copyOf(items);
    }

    private RunRequestedEvent requested(UUID runId, UUID orgId, UUID correlationId, UUID executionId,
                                       List<TestCaseSnapshotItem> snapshot) {
        return new RunRequestedEvent(
            UUID.randomUUID(), correlationId, orgId, runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), snapshot);
    }
}
