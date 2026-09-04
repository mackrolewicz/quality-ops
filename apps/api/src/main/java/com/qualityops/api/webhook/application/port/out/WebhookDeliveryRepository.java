package com.qualityops.api.webhook.application.port.out;

import com.qualityops.api.webhook.domain.WebhookEventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADR-007 §6.3 — the durable outbox. {@code UNIQUE (run_id, webhook_endpoint_id)}
 *  makes a redelivered runs.completed a no-op INSERT. */
public interface WebhookDeliveryRepository {

    int insertIgnoreConflict(UUID orgId, UUID endpointId, UUID runId, WebhookEventType type,
                             String payloadJson);

    List<DueDelivery> selectDue(int batch);

    void markDelivered(UUID id);

    void markRetry(UUID id, int attempt, String lastError, Instant nextAttemptAt);

    void markExhausted(UUID id, int attempt, String lastError);

    int deleteTerminalOlderThan(Instant cutoff);

    record DueDelivery(UUID id, UUID orgId, UUID webhookEndpointId, UUID runId,
                       WebhookEventType eventType, int attempt, String payloadJson) {}
}
