package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-008 §1-2. Bound from {@code qualityops.analytics.*}. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.analytics")
public record AnalyticsProperties(Flaky flaky, Slow slow, Trends trends) {

    public record Flaky(int windowSize, int minRuns) {}

    public record Slow(int defaultLimit) {}

    public record Trends(int maxDays) {}
}
