package com.qualityops.api.config;

import com.qualityops.api.support.AbstractPostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5 (ADR-006 amendment §5): the 10s refresh trigger lives in
 * {@link QueueMetricsRefresher}, gated on {@code qualityops.scheduling.jobs-enabled}.
 * With jobs disabled (the default for {@link AbstractPostgresIT}) the refresher
 * bean is absent, yet {@link QueueMetrics} and its gauges stay registered.
 */
class QueueMetricsRefresherIT extends AbstractPostgresIT {

    @Autowired private ApplicationContext ctx;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void jobsDisabled_refresherBeanAbsent_butQueueMetricsAndGaugesRemain() {
        assertThat(ctx.getBeanNamesForType(QueueMetricsRefresher.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(QueueMetrics.class)).isNotEmpty();
        assertThat(meterRegistry.find("qualityops.queue.depth").gauges()).hasSize(3);
    }
}
