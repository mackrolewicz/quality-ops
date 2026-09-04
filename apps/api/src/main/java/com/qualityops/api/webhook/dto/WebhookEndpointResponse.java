package com.qualityops.api.webhook.dto;

import java.time.Instant;
import java.util.UUID;

/** ADR-007 §6.2 — {@code secret} is NEVER present; {@code secretSet} is always true. */
public record WebhookEndpointResponse(
        UUID id,
        UUID projectId,
        String url,
        boolean secretSet,
        boolean enabled,
        Instant createdAt) {}
