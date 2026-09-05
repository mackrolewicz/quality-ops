package com.qualityops.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-006 §6 (2C amendment §5): drives {@link QueueMetrics#refresh()} every 10s.
 * Gated on {@code qualityops.scheduling.jobs-enabled} so jobs-disabled ITs stay
 * quiet, while the gauges themselves remain registered unconditionally. No
 * {@code @SchedulerLock} — Prometheus gauges are per-scrape-target and each
 * replica refreshes its own AtomicLongs.
 */
@Component
@ConditionalOnProperty(name = "qualityops.scheduling.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class QueueMetricsRefresher {

    private final QueueMetrics metrics;

    public QueueMetricsRefresher(QueueMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "PT10S")
    public void refreshQueueGauges() {
        metrics.refresh();
    }
}
