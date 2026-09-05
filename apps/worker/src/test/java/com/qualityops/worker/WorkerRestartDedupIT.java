package com.qualityops.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.events.ApiAssertion;
import com.qualityops.events.ApiRequestSnapshot;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.support.AbstractWorkerKafkaPostgresIT;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The durable {@code worker.execution_attempt} ledger (ADR-003 §3) — not any
 * in-memory state — is what prevents a real HTTP test from firing twice when
 * {@code runs.requested} is redelivered, including across a Worker restart.
 */
class WorkerRestartDedupIT extends AbstractWorkerKafkaPostgresIT {

    private static final String REQUESTED = "runs.requested";
    private static final String STARTED = "runs.started";
    private static final String COMPLETED = "runs.completed";
    private static final String FAILED = "runs.failed";

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @DynamicPropertySource
    static void executionProps(DynamicPropertyRegistry registry) {
        registry.add("qualityops.worker.execution.mode", () -> "auto");
        registry.add("qualityops.worker.execution.ssrf.allow-private-targets", () -> "true");
        registry.add("qualityops.worker.execution.ssrf.allowed-hosts", () -> "127.0.0.1");
        registry.add("qualityops.worker.execution.ssrf.allowed-ports", () -> "");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getPort() + "/";
    }

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ExecutionAttemptStore attemptStore;

    private MockWebServer server;
    private Consumer<String, String> observer;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        var props = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "it-observer-" + UUID.randomUUID(), "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(props);
        observer.subscribe(List.of(STARTED, COMPLETED, FAILED));
    }

    @AfterEach
    void tearDown() throws Exception {
        observer.close();
        server.shutdown();
    }

    @Test
    void redeliveredRunRequested_afterTerminal_executesOnce_andReemitsCachedTerminal() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok")); // only one real call expected
        var runId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var event = requested(runId, UUID.randomUUID(), UUID.randomUUID(), executionId,
            List.of(oneApiCase(baseUrl())));

        kafkaTemplate.send(REQUESTED, runId.toString(), event);

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(30, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        assertThat(server.getRequestCount()).isEqualTo(1);
        var firstTerminal = firstValue(seen, COMPLETED, runId);

        // Re-publish the identical event (same executionId) — models an at-least-once
        // redelivery; with a Worker restart the in-memory state would be gone, only
        // worker.execution_attempt survives.
        kafkaTemplate.send(REQUESTED, runId.toString(), event);

        await().during(4, SECONDS).atMost(12, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(server.getRequestCount()).as("no second real HTTP call").isEqualTo(1);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, FAILED, runId)).isZero();
            assertThat(count(seen, COMPLETED, runId)).isBetween(1L, 2L); // original + optional cached re-emit
        });

        // Every observed runs.completed is byte-for-byte the same fact.
        var terminals = seen.stream()
            .filter(r -> r.topic().equals(COMPLETED) && runId.toString().equals(r.key()))
            .map(ConsumerRecord::value).toList();
        for (String t : terminals) {
            var e = JSON.readValue(t, RunCompletedEvent.class);
            var first = JSON.readValue(firstTerminal, RunCompletedEvent.class);
            assertThat(e.runId()).isEqualTo(first.runId());
            assertThat(e.executionId()).isEqualTo(first.executionId());
            assertThat(e.outcome()).isEqualTo(first.outcome());
            assertThat(e.caseResults()).isEqualTo(first.caseResults());
        }
    }

    @Test
    void afterTerminal_freshClaimSeesAlreadyCompleted_andRepublishStillDoesNotReexecute() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        var runId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var event = requested(runId, orgId, UUID.randomUUID(), executionId,
            List.of(oneApiCase(baseUrl())));

        kafkaTemplate.send(REQUESTED, runId.toString(), event);
        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(30, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        assertThat(server.getRequestCount()).isEqualTo(1);

        // A brand-new claim on the same executionId (what any restarted Worker instance
        // does) reads the persisted terminal — no in-JVM state involved.
        var claim = attemptStore.claim(executionId, runId, orgId, RunnerKind.API);
        assertThat(claim).isInstanceOf(ExecutionAttemptStore.AlreadyCompleted.class);
        var completed = (ExecutionAttemptStore.AlreadyCompleted) claim;
        assertThat(completed.terminalTopic()).isEqualTo("runs.completed");
        var storedTerminal = JSON.readValue(completed.terminalEventJson(), RunCompletedEvent.class);
        assertThat(storedTerminal.executionId()).isEqualTo(executionId);
        assertThat(storedTerminal.runId()).isEqualTo(runId);

        kafkaTemplate.send(REQUESTED, runId.toString(), event);
        await().during(4, SECONDS).atMost(12, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(server.getRequestCount()).as("ledger row survives, no re-execution").isEqualTo(1);
        });
    }

    // ---- helpers ----

    private TestCaseSnapshotItem oneApiCase(String url) {
        var spec = new ApiRequestSnapshot("GET", url, List.of(), null, 200, 2000, null,
            List.of(new ApiAssertion(ApiAssertion.Type.STATUS_EQUALS, "", "200")));
        return new TestCaseSnapshotItem(UUID.randomUUID(), "c0", 0, spec);
    }

    private RunRequestedEvent requested(UUID runId, UUID orgId, UUID correlationId, UUID executionId,
            List<TestCaseSnapshotItem> cases) {
        return new RunRequestedEvent(
            UUID.randomUUID(), correlationId, orgId, runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), cases);
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
            .findFirst().orElseThrow();
    }
}
