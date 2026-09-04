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
import com.qualityops.worker.execution.application.port.out.PlaywrightBrowser;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("browser")
class WorkerBrowserExecutionIT extends AbstractWorkerKafkaPostgresIT {

    private static final String REQUESTED = "runs.requested";
    private static final String STARTED = "runs.started";
    private static final String COMPLETED = "runs.completed";
    private static final String FAILED = "runs.failed";

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Path ARTIFACT_DIR = createArtifactDir();

    private static Path createArtifactDir() {
        try {
            return Files.createTempDirectory("qualityops-browser-it");
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
        registry.add("qualityops.worker.execution.browser.step-timeout", () -> "2s");
        registry.add("qualityops.worker.execution.browser.artifact-temp-dir", ARTIFACT_DIR::toString);
    }

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlaywrightBrowser driver;

    private BrowserTestFixtureServer fixture;
    private Consumer<String, String> observer;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new BrowserTestFixtureServer();
        fixture.start();
        var cp = KafkaTestUtils.consumerProps(
            broker.getBrokersAsString(), "browser-it-" + UUID.randomUUID(), "true");
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
    void browserRun_threeCases_passFailFail_emitsOneStartedOneCompleted_zeroFailed() throws Exception {
        var runId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var button = new Selector(Selector.Strategy.ROLE, null, "button", "Go");
        var msg = new Selector(Selector.Strategy.TEST_ID, "msg", null, null);
        var email = new Selector(Selector.Strategy.LABEL, "Email", null, null);

        var caseA = browserCase("A", new BrowserTestSnapshot(fixture.baseUrl() + "/",
            List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, fixture.baseUrl() + "/", null),
                    new BrowserStep(BrowserStep.Action.FILL, email, "a@b.test", null),
                    new BrowserStep(BrowserStep.Action.CLICK, button, null, null)),
            List.of(new BrowserAssertion(BrowserAssertion.Type.TEXT_CONTAINS, msg, "Saved")),
            null, null, null), 0);
        var caseB = browserCase("B", new BrowserTestSnapshot(fixture.baseUrl() + "/",
            List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, fixture.baseUrl() + "/", null)),
            List.of(new BrowserAssertion(BrowserAssertion.Type.TEXT_EQUALS, msg, "Nope")),
            null, null, null), 1);
        var caseC = browserCase("C", new BrowserTestSnapshot(fixture.baseUrl() + "/",
            List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, fixture.baseUrl() + "/", null)),
            List.of(new BrowserAssertion(BrowserAssertion.Type.VISIBLE, msg, null)),
            null, null, null), 2);

        kafkaTemplate.send(REQUESTED, runId.toString(),
            requested(runId, orgId, executionId, List.of(caseA, caseB, caseC)));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(120, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, STARTED, runId)).isEqualTo(1);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        await().during(2, SECONDS).atMost(10, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, FAILED, runId)).isZero();
        });

        var completed = JSON.readValue(firstValue(seen, COMPLETED, runId), RunCompletedEvent.class);
        assertThat(completed.schemaVersion()).isEqualTo(RunCompletedEvent.SCHEMA_VERSION);
        assertThat(completed.outcome().name()).isEqualTo("FAILED");
        assertThat(completed.caseResults()).extracting(CaseResultSummary::verdict)
            .as("case reasons: %s", completed.caseResults().stream()
                .map(CaseResultSummary::firstFailureReason).toList())
            .containsExactly(CaseResultSummary.Verdict.PASSED,
                CaseResultSummary.Verdict.FAILED, CaseResultSummary.Verdict.FAILED);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT runner_kind, org_id FROM worker.execution_attempt WHERE execution_id = ?", executionId);
        assertThat(row.get("runner_kind")).isEqualTo("BROWSER");
        assertThat(row.get("org_id").toString()).isEqualTo(orgId.toString());

        try (Stream<Path> pngs = Files.list(ARTIFACT_DIR)) {
            assertThat(pngs.filter(p -> p.toString().endsWith(".png")).count())
                .as("screenshot per failed case").isGreaterThanOrEqualTo(2);
        }

        assertThat(driver.openContextCount()).as("no BrowserContext leaked").isZero();
    }

    @Test
    void browserRun_midStepTimeout_caseTimesOut_runStillCompletes() throws Exception {
        var runId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var missing = new Selector(Selector.Strategy.CSS, "#does-not-exist", null, null);

        var kase = browserCase("timeout", new BrowserTestSnapshot(fixture.baseUrl() + "/",
            List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, fixture.baseUrl() + "/", null),
                    new BrowserStep(BrowserStep.Action.CLICK, missing, null, null)),
            List.of(new BrowserAssertion(BrowserAssertion.Type.URL_CONTAINS, null, "/")),
            null, 500, null), 0);

        kafkaTemplate.send(REQUESTED, runId.toString(),
            requested(runId, UUID.randomUUID(), executionId, List.of(kase)));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(90, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        var completed = JSON.readValue(firstValue(seen, COMPLETED, runId), RunCompletedEvent.class);
        assertThat(count(seen, FAILED, runId)).isZero();
        assertThat(completed.caseResults().get(0).verdict())
            .isIn(CaseResultSummary.Verdict.TIMEOUT, CaseResultSummary.Verdict.FAILED);
        assertThat(driver.openContextCount()).isZero();
    }

    @Test
    void browserRun_secondNavigateToMetadata_caseBlocked_runNotAborted() throws Exception {
        var runId = UUID.randomUUID();
        var executionId = UUID.randomUUID();

        var kase = browserCase("blocked", new BrowserTestSnapshot(fixture.baseUrl() + "/",
            List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, fixture.baseUrl() + "/", null),
                    new BrowserStep(BrowserStep.Action.NAVIGATE, null,
                        "http://169.254.169.254/latest/meta-data", null)),
            List.of(new BrowserAssertion(BrowserAssertion.Type.URL_CONTAINS, null, "/")),
            null, null, null), 0);

        kafkaTemplate.send(REQUESTED, runId.toString(),
            requested(runId, UUID.randomUUID(), executionId, List.of(kase)));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(60, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        var completed = JSON.readValue(firstValue(seen, COMPLETED, runId), RunCompletedEvent.class);
        assertThat(completed.caseResults().get(0).verdict()).isEqualTo(CaseResultSummary.Verdict.BLOCKED);
        assertThat(count(seen, FAILED, runId)).isZero();
    }

    @Test
    void browserRun_pageWithMetadataSubresource_blockedSubresource_completesWithinBudget() throws Exception {
        var runId = UUID.randomUUID();
        var executionId = UUID.randomUUID();

        var kase = browserCase("subresource", new BrowserTestSnapshot(fixture.baseUrl() + "/metadata-img",
            List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null,
                fixture.baseUrl() + "/metadata-img", null)),
            List.of(new BrowserAssertion(BrowserAssertion.Type.URL_CONTAINS, null, "/metadata-img")),
            null, null, null), 0);

        kafkaTemplate.send(REQUESTED, runId.toString(),
            requested(runId, UUID.randomUUID(), executionId, List.of(kase)));

        var seen = new ArrayList<ConsumerRecord<String, String>>();
        await().atMost(60, SECONDS).untilAsserted(() -> {
            drain(seen);
            assertThat(count(seen, COMPLETED, runId)).isEqualTo(1);
        });
        var completed = JSON.readValue(firstValue(seen, COMPLETED, runId), RunCompletedEvent.class);
        assertThat(completed.caseResults().get(0).verdict()).isEqualTo(CaseResultSummary.Verdict.PASSED);
    }

    // ---- helpers ----

    private static TestCaseSnapshotItem browserCase(String name, BrowserTestSnapshot spec, int idx) {
        return new TestCaseSnapshotItem(UUID.randomUUID(), name, idx, null, spec);
    }

    private RunRequestedEvent requested(UUID runId, UUID orgId, UUID executionId,
                                        List<TestCaseSnapshotItem> cases) {
        return new RunRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), cases);
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
