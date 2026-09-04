package com.qualityops.api.webhook.domain;

/** ADR-007 §6.3. Persisted as VARCHAR; {@link #wireName()} is the value sent in
 *  the {@code X-QualityOps-Event} header and the {@code event} payload field. */
public enum WebhookEventType {
    RUN_COMPLETED,
    RUN_FAILED;

    public String wireName() {
        return this == RUN_COMPLETED ? "run.completed" : "run.failed";
    }
}
