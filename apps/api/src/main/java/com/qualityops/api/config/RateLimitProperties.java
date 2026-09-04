package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-008 §6. Bound from {@code qualityops.ratelimit.*}. Per-operation
 *  {@code limit}/{@code window} are resolved from placeholders on the
 *  {@code @RateLimited} annotation itself, not here. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.ratelimit")
public record RateLimitProperties(boolean enabled, boolean failOpen) {}
