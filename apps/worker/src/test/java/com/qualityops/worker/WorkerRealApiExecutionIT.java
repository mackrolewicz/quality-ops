package com.qualityops.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.events.ApiAssertion;
import com.qualityops.events.ApiRequestSnapshot;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.TestCaseSnapshotItem;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * End-to-end: a v2 {@code runs.requested} whose cases carry {@link ApiRequestSnapshot}
 * drives real HTTP execution through Kafka; verdicts, the aggregate outcome and the
 * durable {@code worker.execution_attempt} ledger row all reflect the real calls.
 */
class WorkerRealApiExecutionIT extends AbstractWorkerKafkaPostgresIT {

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
        registry.add("qualityops.worker.execution.ssrf.allowed-ports", () -> ""); // MockWebServer port varies
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getPort() + "/";
    }

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private JdbcTemplate jdbc;

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
    void v2RunRequested_withApiRequestCases_executesRealHttp_andWritesApiLedgerRow() throws Exception {
        // cases 0,1 → 200 (pass), cases 2,3 → 500 (fail vs expectedStatus 200)
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        var runId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var base = baseUrl();
        var cases = List.of(
            apiCase("c0", 0, base), apiCase("c1", 1, base),
            apiCase("c2", 2, base), apiCase("c3", 3, base));

        kafkaTemplate.send(REQUESTED, runId.toString(),
            requested(runId, orgId, UUID.randomUUID(), executionId, cases));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(30, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
            assertThat(count(seen, FAILED, runId)).isZero();
        });

        assertThat(server.getRequestCount()).isEqualTo(4);

        var completed = JSON.readValue(firstValue(seen, COMPLETED, runId), RunCompletedEvent.class);
        assertThat(completed.schemaVersion()).isEqualTo(RunCompletedEvent.SCHEMA_VERSION);
        assertThat(completed.runId()).isEqualTo(runId);
        assertThat(completed.executionId()).isEqualTo(executionId);
        assertThat(completed.outcome()).isEqualTo(RunOutcome.FAILED); // mixed 2 pass / 2 fail
        assertThat(completed.caseResults()).hasSize(4);
        assertThat(completed.caseResults()).allSatisfy(cr ->
            assertThat(cr.durationMillis()).isGreaterThanOrEqualTo(0L));
        assertThat(completed.caseResults().stream()
            .filter(cr -> cr.verdict() == com.qualityops.events.CaseResultSummary.Verdict.PASSED).count())
            .isEqualTo(2);
        assertThat(completed.caseResults().stream()
            .filter(cr -> cr.verdict() == com.qualityops.events.CaseResultSummary.Verdict.FAILED).count())
            .isEqualTo(2);

        var row = jdbc.queryForMap(
            "SELECT status, runner_kind, org_id, terminal_topic, terminal_event_json "
                + "FROM worker.execution_attempt WHERE execution_id = ?", executionId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("runner_kind")).isEqualTo("API");
        assertThat(row.get("org_id")).isEqualTo(orgId);              // multi-tenancy on the ledger row
        assertThat(row.get("terminal_topic")).isEqualTo("runs.completed");
        assertThat(row.get("terminal_event_json")).isNotNull();
    }

    // ---- helpers ----

    private TestCaseSnapshotItem apiCase(String name, int idx, String url) {
        var spec = new ApiRequestSnapshot("GET", url, List.of(), null, 200, 2000, null,
            List.of(new ApiAssertion(ApiAssertion.Type.STATUS_EQUALS, "", "200")));
        return new TestCaseSnapshotItem(UUID.randomUUID(), name, idx, spec);
    }

    private com.qualityops.events.RunRequestedEvent requested(UUID runId, UUID orgId, UUID correlationId,
            UUID executionId, List<TestCaseSnapshotItem> cases) {
        return new com.qualityops.events.RunRequestedEvent(
            UUID.randomUUID(), correlationId, orgId, runId, executionId,
            Instant.now(), com.qualityops.events.RunRequestedEvent.SCHEMA_VERSION,
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
