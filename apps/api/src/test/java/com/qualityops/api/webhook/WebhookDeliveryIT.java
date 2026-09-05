package com.qualityops.api.webhook;

import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.api.webhook.application.port.out.WebhookEndpointRepository;
import com.qualityops.api.webhook.application.service.WebhookDeliveryService;
import com.qualityops.api.webhook.domain.WebhookSignature;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-007 §6.3 — signed, durable, at-least-once completion webhooks. */
@TestPropertySource(properties = {
    "qualityops.webhook.initial-backoff=PT0.1S",
    "qualityops.webhook.max-attempts=3"
})
class WebhookDeliveryIT extends AbstractKafkaPostgresIT {

    private static final String SECRET = "0123456789abcdef0123";

    @Autowired private EnqueueRunUseCase enqueueRunUseCase;
    @Autowired private QueueDispatchService queueDispatchService;
    @Autowired private ApplyRunLifecycleUseCase applyRunLifecycleUseCase;
    @Autowired private WebhookDeliveryService webhookDeliveryService;
    @Autowired private WebhookEndpointRepository webhookEndpointRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private MockWebServer server;

    private UUID orgId;
    private UUID projectId;
    private UUID suiteId;
    private UUID environmentId;
    private UUID triggeredBy;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        orgId = ItFixtures.insertOrg(jdbc);
        projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        ItFixtures.insertCases(jdbc, orgId, suiteId, 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
    }

    private UUID runToTerminal(UUID runOrg, UUID runProject, UUID runSuite, UUID runEnv, UUID runUser) {
        var runId = enqueueRunUseCase.enqueue(new EnqueueRunCommand(runOrg, runProject, runSuite,
            runEnv, runUser, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
        queueDispatchService.dispatchAvailable();
        var executionId = jdbc.queryForObject(
            "SELECT execution_id FROM test_runs WHERE id = ?", UUID.class, runId);
        applyRunLifecycleUseCase.onRunCompleted(new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
            runOrg, runId, executionId, Instant.now(), RunCompletedEvent.SCHEMA_VERSION,
            runProject, runSuite, RunOutcome.PASSED, List.of(), null));
        return runId;
    }

    private UUID runToTerminal() {
        return runToTerminal(orgId, projectId, suiteId, environmentId, triggeredBy);
    }

    @Test
    void runReachesTerminal_deliversSignedWebhook() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        var ep = webhookEndpointRepository.create(orgId, projectId,
            server.url("/hook").toString(), SECRET, true, triggeredBy);
        var runId = runToTerminal();

        webhookDeliveryService.dispatchDue();

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        String body = req.getBody().readUtf8();
        long ts = Long.parseLong(req.getHeader("X-QualityOps-Timestamp"));
        assertThat(req.getHeader("X-QualityOps-Signature"))
            .isEqualTo(WebhookSignature.sign(SECRET, ts, body));
        assertThat(req.getHeader("X-QualityOps-Event")).isEqualTo("run.completed");

        String deliveryId = jdbc.queryForObject(
            "SELECT id FROM webhook_delivery WHERE run_id = ? AND webhook_endpoint_id = ?",
            String.class, runId, ep.id());
        assertThat(req.getHeader("X-QualityOps-Delivery")).isEqualTo(deliveryId);
        assertThat(jdbc.queryForObject("SELECT state FROM webhook_delivery WHERE id = ?::uuid",
            String.class, deliveryId)).isEqualTo("DELIVERED");
        assertThat(meterRegistry.find("qualityops.webhook.delivery").tag("outcome", "delivered")
            .counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.find("qualityops.webhook.delivery_duration").timer().count())
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void endpoint500_retriesWithBackoff_thenExhausted() {
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        var ep = webhookEndpointRepository.create(orgId, projectId,
            server.url("/hook").toString(), SECRET, true, triggeredBy);
        var runId = runToTerminal();
        String deliveryId = jdbc.queryForObject(
            "SELECT id FROM webhook_delivery WHERE run_id = ? AND webhook_endpoint_id = ?",
            String.class, runId, ep.id());

        for (int attempt = 1; attempt <= 3; attempt++) {
            jdbc.update("UPDATE webhook_delivery SET next_attempt_at = now() - interval '1 second' "
                + "WHERE id = ?::uuid", deliveryId);
            webhookDeliveryService.dispatchDue();
        }

        assertThat(jdbc.queryForObject("SELECT state FROM webhook_delivery WHERE id = ?::uuid",
            String.class, deliveryId)).isEqualTo("EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM webhook_delivery WHERE id = ?::uuid",
            String.class, deliveryId)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT attempt FROM webhook_delivery WHERE id = ?::uuid",
            Integer.class, deliveryId)).isGreaterThanOrEqualTo(3);
        assertThat(meterRegistry.find("qualityops.webhook.delivery").tag("outcome", "exhausted")
            .counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void redeliveredRunsCompleted_noDuplicateDeliveryRow() {
        webhookEndpointRepository.create(orgId, projectId,
            server.url("/hook").toString(), SECRET, true, triggeredBy);
        var runId = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
        queueDispatchService.dispatchAvailable();
        var executionId = jdbc.queryForObject(
            "SELECT execution_id FROM test_runs WHERE id = ?", UUID.class, runId);
        var event = new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, projectId, suiteId, RunOutcome.PASSED,
            List.of(), null);

        applyRunLifecycleUseCase.onRunCompleted(event);
        applyRunLifecycleUseCase.onRunCompleted(event);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE run_id = ?",
            Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void orgBRun_doesNotDeliverToOrgAEndpoint() {
        webhookEndpointRepository.create(orgId, null,
            server.url("/hook").toString(), SECRET, true, triggeredBy); // org-A, org-wide

        var orgB = ItFixtures.insertOrg(jdbc);
        var projectB = ItFixtures.insertProject(jdbc, orgB);
        var suiteB = ItFixtures.insertSuite(jdbc, orgB, projectB);
        var envB = ItFixtures.insertEnvironment(jdbc, orgB, projectB);
        var userB = ItFixtures.insertUser(jdbc, orgB);
        ItFixtures.insertCases(jdbc, orgB, suiteB, 1);

        var runB = runToTerminal(orgB, projectB, suiteB, envB, userB);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE run_id = ?",
            Integer.class, runB)).isZero();
    }

    @Test
    void disabledEndpoint_noDeliveryRow() {
        webhookEndpointRepository.create(orgId, projectId,
            server.url("/hook").toString(), SECRET, false, triggeredBy);

        var runId = runToTerminal();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE run_id = ?",
            Integer.class, runId)).isZero();
    }

    @Test
    void pendingRow_survivesRestart_isPickedUpNextTick() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202));
        var ep = webhookEndpointRepository.create(orgId, projectId,
            server.url("/hook").toString(), SECRET, true, triggeredBy);
        var runId = enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgId, projectId, suiteId,
            environmentId, triggeredBy, RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
        jdbc.update("INSERT INTO webhook_delivery (org_id, webhook_endpoint_id, run_id, event_type, "
            + "payload_json, state, attempt, next_attempt_at) "
            + "VALUES (?, ?, ?, 'RUN_COMPLETED', '{}'::jsonb, 'PENDING', 0, now())",
            orgId, ep.id(), runId);

        webhookDeliveryService.dispatchDue();

        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT state FROM webhook_delivery WHERE run_id = ?",
            String.class, runId)).isEqualTo("DELIVERED");
    }
}
