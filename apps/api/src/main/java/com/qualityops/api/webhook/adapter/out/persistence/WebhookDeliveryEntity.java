package com.qualityops.api.webhook.adapter.out.persistence;

import com.qualityops.api.webhook.domain.WebhookDeliveryState;
import com.qualityops.api.webhook.domain.WebhookEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** ADR-007 §6.3 — durable outbox row. {@code state}/{@code event_type} are
 *  VARCHAR + CHECK -> plain STRING enums. {@code payload_json} is jsonb, frozen
 *  at enqueue for signature stability. */
@Entity
@Table(name = "webhook_delivery")
class WebhookDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "webhook_endpoint_id", nullable = false, updatable = false)
    private UUID webhookEndpointId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32, updatable = false)
    private WebhookEventType eventType;

    @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private WebhookDeliveryState state;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookDeliveryEntity() {}

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }
}
