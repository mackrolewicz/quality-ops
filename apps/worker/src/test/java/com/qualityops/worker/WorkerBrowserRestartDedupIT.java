package com.qualityops.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.events.BrowserAssertion;
import com.qualityops.events.BrowserTestSnapshot;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.Selector;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.support.AbstractWorkerKafkaPostgresIT;
import com.qualityops.worker.support.BrowserTestFixtureServer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

/** The durable {@code worker.execution_attempt} ledger — not in-memory state —
 *  keeps a redelivered browser {@code runs.requested} from running Chromium twice. */
@Tag("browser")
class WorkerBrowserRestartDedupIT extends AbstractWorkerKafkaPostgresIT {

    private static final String REQUESTED = "runs.requested";
    private static final String STARTED = "runs.started";
    private static final String COMPLETED = "runs.completed";
    private static final String FAILED = "runs.failed";

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("qualityops.worker.execution.mode", () -> "auto");
        registry.add("qualityops.worker.execution.ssrf.allow-private-targets", () -> "true");
        registry.add("qualityops.worker.execution.ssrf.allowed-hosts", () -> "127.0.0.1");
        registry.add("qualityops.worker.execution.ssrf.allowed-ports", () -> "");
        registry.add("qualityops.worker.execution.browser.headless", () -> "true");
    }

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ExecutionAttemptStore attemptStore;

    private BrowserTestFixtureServer fixture;
    private Consumer<String, String> observer;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new BrowserTestFixtureServer();
        fixture.start();
        var cp = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "browser-dedup-it-" + UUID.randomUUID(), "true");
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(cp);
        observer.subscribe(List.of(STARTED, COMPLETED, FAILED));
    }

    @AfterEach
    void tearDown() {
        observer.close();
        fixture.stop();
    }

    @Test
    void redeliveredBrowserRunRequested_executesScenarioOnce_andReemitsCachedTerminal() throws Exception {
        var runId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var msg = new Selector(Selector.Strategy.TEST_ID, "msg", null, null);
        // startUrl is the only navigation to "/" — one fixture hit per execution.
        var kase = new TestCaseSnapshotItem(UUID.randomUUID(), "dedup", 0, null,
            new BrowserTestSnapshot(fixture.baseUrl() + "/",
                List.of(),
                List.of(new BrowserAssertion(BrowserAssertion.Type.URL_CONTAINS, null, "/")),
                null, null, null));
        var event = new RunRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(kase));

        kafkaTemplate.send(REQUESTED, runId.toString(), event);

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(90, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        var firstTerminal = firstValue(seen, COMPLETED, runId);

        kafkaTemplate.send(REQUESTED, runId.toString(), event);

        await().during(4, SECONDS).atMost(15, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(fixture.hitCount()).as("scenario navigated to / exactly once").isEqualTo(1);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, FAILED, runId)).isZero();
            assertThat(count(seen, COMPLETED, runId)).isBetween(1L, 2L);
        });

        for (String t : seen.stream()
                .filter(r -> r.topic().equals(COMPLETED) && runId.toString().equals(r.key()))
                .map(ConsumerRecord::value).toList()) {
            assertThat(t).isEqualTo(firstTerminal);
        }

        var claim = attemptStore.claim(executionId, runId, orgId, RunnerKind.BROWSER);
        assertThat(claim).isInstanceOf(ExecutionAttemptStore.AlreadyCompleted.class);
        var completed = (ExecutionAttemptStore.AlreadyCompleted) claim;
        assertThat(completed.terminalTopic()).isEqualTo("runs.completed");
        var stored = JSON.readValue(completed.terminalEventJson(), RunCompletedEvent.class);
        assertThat(stored.executionId()).isEqualTo(executionId);
        assertThat(stored.runId()).isEqualTo(runId);
    }

    private void drain(List<ConsumerRecord<String, String>> sink) {
        observer.poll(Duration.ofMillis(400)).forEach(sink::add);
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
