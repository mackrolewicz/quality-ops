package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** ADR-008 §4. Bound from {@code qualityops.cache.*}. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.cache")
public record CacheProperties(boolean enabled, Duration dashboardTtl) {}
