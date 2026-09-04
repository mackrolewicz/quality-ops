package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code qualityops.timing.*} (ADR-008 §7). The global fallback slow-op
 * threshold used by {@code TimingAspect} whenever a {@code @Timed} site does not
 * set its own {@code slowThresholdMillis}.
 */
@ConfigurationProperties(prefix = "qualityops.timing")
public record TimingProperties(long slowThresholdMs) {

    public TimingProperties {
        if (slowThresholdMs <= 0) {
            slowThresholdMs = 1000;
        }
    }
}
