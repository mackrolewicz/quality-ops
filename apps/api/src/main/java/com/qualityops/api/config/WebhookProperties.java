package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** ADR-007 §6. Bound from {@code qualityops.webhook.*}. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.webhook")
public record WebhookProperties(
        boolean enabled,
        Duration dispatchInterval,
        int batchSize,
        int maxAttempts,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration initialBackoff,
        Duration deliveryRetention,
        Duration replayWindow
) {}
