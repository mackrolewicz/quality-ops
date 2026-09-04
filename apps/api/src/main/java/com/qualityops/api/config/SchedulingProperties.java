package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** ADR-006 §4.2 / ADR-007 §9. Bound from {@code qualityops.scheduling.*} in
 *  application.yml. Auto-registered by @ConfigurationPropertiesScan on
 *  QualityOpsApplication. */
@ConfigurationProperties("qualityops.scheduling")
public record SchedulingProperties(
        boolean jobsEnabled,
        Duration tickInterval,
        int tickBatchSize,
        Duration fireLedgerRetention,
        Queue queue,
        Reaper reaper,
        Retry retry
) {
    public record Queue(
            Duration dispatchInterval,
            int dispatchBatchSize,
            int maxActiveRunsPerOrg,
            Duration agingStep,
            int agingMaxBoost,
            int dispatchMaxAttempts,
            Duration sendTimeout,
            Duration retention
    ) {}

    /** ADR-007 §1.4 — stuck-run reaper timings. */
    public record Reaper(
            Duration interval,
            Duration dispatchGrace,
            Duration runTimeout,
            int batchSize
    ) {}

    /** ADR-007 §2.3 — queue-driven retry policy + budgets. */
    public record Retry(
            boolean enabled,
            int maxPerRun,
            int maxActivePerOrg,
            Duration window,
            List<String> nonRetryableReasonPrefixes
    ) {}
}
