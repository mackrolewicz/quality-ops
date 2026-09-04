package com.qualityops.api.webhook.domain;

/** ADR-007 §6.3 — {@code webhook_delivery.state}. VARCHAR + CHECK in the DB. */
public enum WebhookDeliveryState {
    PENDING,
    DELIVERED,
    EXHAUSTED
}
