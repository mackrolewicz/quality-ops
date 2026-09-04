package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** ADR-008 §3. Bound from {@code qualityops.scheduling.environment-health.*}. A
 *  standalone record (not a component of {@link SchedulingProperties}) so the
 *  environment-health probe can inject only what it needs. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.scheduling.environment-health")
public record EnvironmentHealthProperties(
        boolean enabled,
        Duration interval,
        Duration probeInterval,
        Duration probeTimeout,
        int failureThreshold,
        int degradedAfter,
        int batchSize,
        Duration historyRetention,
        boolean allowPrivateTargets
) {}
