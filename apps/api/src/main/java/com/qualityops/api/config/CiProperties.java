package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** ADR-007 §5.3. Bound from {@code qualityops.ci.*}. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.ci")
public record CiProperties(Duration idempotencyRetention) {}
