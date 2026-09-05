package com.qualityops.api.webhook.application.port.in;

import com.qualityops.api.webhook.domain.WebhookEventType;

import java.util.UUID;

/** ADR-007 §6.3 — the seam {@code RunLifecycleService} calls, in the SAME
 *  transaction as the guarded terminal transition, gated on its {@code moved}
 *  boolean. Inserts frozen-payload {@code webhook_delivery} rows; the async
 *  {@code WebhookDispatchJob} sends them. */
public interface EnqueueRunWebhooksUseCase {

    void enqueueForTerminalRun(UUID runId, UUID orgId, WebhookEventType type);
}
