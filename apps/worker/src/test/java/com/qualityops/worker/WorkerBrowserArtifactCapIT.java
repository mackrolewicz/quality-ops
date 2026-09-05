package com.qualityops.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.events.BrowserAssertion;
import com.qualityops.events.BrowserStep;
import com.qualityops.events.BrowserTestSnapshot;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.Selector;
import com.qualityops.events.TestCaseSnapshotItem;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** artifact-max-bytes = 1 ⇒ a failing case still completes, but its screenshot is
 *  over the cap and is never written to disk (bytes counted only, path dropped). */
@Tag("browser")
class WorkerBrowserArtifactCapIT extends AbstractWorkerKafkaPostgresIT {

    private static final String REQUESTED = "runs.requested";
    private static final String COMPLETED = "runs.completed";
    private static final String FAILED = "runs.failed";

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Path ARTIFACT_DIR = createDir();

    private static Path createDir() {
        try {
            return Files.createTempDirectory("qualityops-browser-cap");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("qualityops.worker.execution.mode", () -> "auto");
        registry.add("qualityops.worker.execution.ssrf.allow-private-targets", () -> "true");
        registry.add("qualityops.worker.execution.ssrf.allowed-hosts", () -> "127.0.0.1");
        registry.add("qualityops.worker.execution.ssrf.allowed-ports", () -> "");
        registry.add("qualityops.worker.execution.browser.headless", () -> "true");
        registry.add("qualityops.worker.execution.browser.artifact-max-bytes", () -> "1");
        registry.add("qualityops.worker.execution.browser.artifact-temp-dir", ARTIFACT_DIR::toString);
    }

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;

    private BrowserTestFixtureServer fixture;
    private Consumer<String, String> observer;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new BrowserTestFixtureServer();
        fixture.start();
        var cp = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "browser-cap-it-" + UUID.randomUUID(), "true");
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(cp);
        observer.subscribe(List.of(COMPLETED, FAILED));
    }

    @AfterEach
    void tearDown() {
        observer.close();
        fixture.stop();
    }

    @Test
    void failingCase_screenshotOverCap_noPngWritten_runStillCompletes() throws Exception {
        var runId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var msg = new Selector(Selector.Strategy.TEST_ID, "msg", null, null);
        var kase = new TestCaseSnapshotItem(UUID.randomUUID(), "cap", 0, null,
            new BrowserTestSnapshot(fixture.baseUrl() + "/",
                List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, fixture.baseUrl() + "/", null)),
                List.of(new BrowserAssertion(BrowserAssertion.Type.VISIBLE, msg, null)),
                null, null, null));

        kafkaTemplate.send(REQUESTED, runId.toString(), new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(kase)));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(90, SECONDS).untilAsserted(() -> {
            observer.poll(Duration.ofMillis(400)).forEach(seen::add);
            assertThat(seen.stream().filter(r -> r.topic().equals(COMPLETED)
                && runId.toString().equals(r.key())).count()).isEqualTo(1);
        });

        var completed = JSON.readValue(seen.stream()
            .filter(r -> r.topic().equals(COMPLETED) && runId.toString().equals(r.key()))
            .map(ConsumerRecord::value).findFirst().orElseThrow(), RunCompletedEvent.class);
        assertThat(completed.caseResults().get(0).verdict()).isEqualTo(CaseResultSummary.Verdict.FAILED);

        try (Stream<Path> files = Files.list(ARTIFACT_DIR)) {
            assertThat(files.filter(p -> p.toString().endsWith(".png")).count())
                .as("screenshot over the 1-byte cap is never written").isZero();
        }
    }
}
