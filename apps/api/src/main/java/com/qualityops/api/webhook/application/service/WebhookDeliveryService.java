package com.qualityops.api.webhook.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.WebhookProperties;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.webhook.application.port.in.EnqueueRunWebhooksUseCase;
import com.qualityops.api.webhook.application.port.out.WebhookDeliveryRepository;
import com.qualityops.api.webhook.application.port.out.WebhookDeliveryRepository.DueDelivery;
import com.qualityops.api.webhook.application.port.out.WebhookEndpointRepository;
import com.qualityops.api.webhook.domain.WebhookEventType;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADR-007 §6.3. NOT class-{@code @Transactional}. {@code enqueueForTerminalRun}
 *  is {@code MANDATORY} — it always joins the lifecycle handler's transaction so
 *  the delivery rows commit atomically with the terminal transition. The per-row
 *  send loop in {@code dispatchDue()} uses its own {@link TransactionTemplate}
 *  unit and is invoked by {@code WebhookDispatchJob} and directly by ITs. */
@Service
public class WebhookDeliveryService implements EnqueueRunWebhooksUseCase {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final WebhookEndpointRepository webhookEndpointRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final GetRunUseCase getRunUseCase;
    private final WebhookSender webhookSender;
    private final WebhookProperties props;
    private final QueueMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public WebhookDeliveryService(WebhookEndpointRepository webhookEndpointRepository,
                                  WebhookDeliveryRepository webhookDeliveryRepository,
                                  GetRunUseCase getRunUseCase,
                                  WebhookSender webhookSender,
                                  WebhookProperties props,
                                  QueueMetrics metrics,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.getRunUseCase = getRunUseCase;
        this.webhookSender = webhookSender;
        this.props = props;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueForTerminalRun(UUID runId, UUID orgId, WebhookEventType type) {
        if (!props.enabled()) {
            return;
        }
        var run = getRunUseCase.getDomain(runId, orgId);
        var endpoints = webhookEndpointRepository.findEnabledForOrgAndProject(orgId, run.projectId());
        if (endpoints.isEmpty()) {
            return;
        }
        String payload = buildPayloadJson(run, type); // FROZEN now for signature stability
        for (var ep : endpoints) {
            webhookDeliveryRepository.insertIgnoreConflict(orgId, ep.id(), runId, type, payload);
        }
    }

    /** Sends every due delivery. Public — {@code WebhookDispatchJob} and ITs call it. */
    public void dispatchDue() {
        if (!props.enabled()) {
            return;
        }
        for (DueDelivery d : webhookDeliveryRepository.selectDue(props.batchSize())) {
            try {
                dispatchOne(d);
            } catch (RuntimeException e) {
                // One unmappable / broken row must not abandon the rest of the batch.
                log.warn("Webhook delivery {} failed to dispatch this pass: {}", d.id(), e.toString());
            }
        }
    }

    private void dispatchOne(DueDelivery d) {
        var ep = webhookEndpointRepository.findById(d.webhookEndpointId(), d.orgId()).orElse(null);
        if (ep == null || !ep.enabled()) {
            webhookDeliveryRepository.markExhausted(d.id(), d.attempt(), "endpoint removed/disabled");
            return;
        }
        long ts = Instant.now().getEpochSecond();
        var sample = Timer.start();
        var out = webhookSender.send(ep.url(), ep.secret(), d.eventType(), d.id(), ts, d.payloadJson());
        sample.stop(metrics.webhookDeliveryDuration());
        txTemplate.executeWithoutResult(status -> applyOutcome(d, out));
    }

    private void applyOutcome(DueDelivery d, WebhookSender.SendOutcome out) {
        if (out.delivered()) {
            webhookDeliveryRepository.markDelivered(d.id());
            metrics.webhookDelivery("delivered");
            return;
        }
        int attempt = d.attempt() + 1;
        if (attempt >= props.maxAttempts()) {
            webhookDeliveryRepository.markExhausted(d.id(), attempt, out.error());
            metrics.webhookDelivery("exhausted");
            log.warn("Webhook delivery {} EXHAUSTED after {} attempts: {}", d.id(), attempt, out.error());
        } else {
            Duration backoff = capBackoff(props.initialBackoff().multipliedBy(1L << (attempt - 1)));
            webhookDeliveryRepository.markRetry(d.id(), attempt, out.error(), Instant.now().plus(backoff));
            metrics.webhookDelivery("failed");
        }
    }

    private static Duration capBackoff(Duration d) {
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private String buildPayloadJson(TestRun run, WebhookEventType type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", type.wireName());
        payload.put("runId", run.id().toString());
        payload.put("projectId", run.projectId().toString());
        payload.put("suiteId", run.suiteId().toString());
        payload.put("environmentId", run.environmentId().toString());
        payload.put("status", run.status().name());
        payload.put("startedAt", run.startedAt() == null ? null : run.startedAt().toString());
        payload.put("completedAt", run.completedAt() == null ? null : run.completedAt().toString());
        payload.put("triggeredBy", run.triggeredBy().toString());
        Map<String, String> links = new LinkedHashMap<>();
        links.put("self", "/api/v1/runs/" + run.id());
        links.put("results", "/api/v1/runs/" + run.id() + "/results");
        links.put("artifacts", "/api/v1/runs/" + run.id() + "/artifacts");
        payload.put("links", links);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise webhook payload for run " + run.id(), e);
        }
    }
}
