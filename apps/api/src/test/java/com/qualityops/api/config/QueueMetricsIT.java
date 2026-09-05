package com.qualityops.api.config;

import com.qualityops.api.support.AbstractPostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 §6: the queue Micrometer meters are registered with bounded
 * cardinality (priority tag only, no org tag) and the SchedulingProperties bind
 * from application.yml (including the {@code 60s} / {@code 90d} shorthand).
 */
class QueueMetricsIT extends AbstractPostgresIT {

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private QueueMetrics queueMetrics;
    @Autowired private SchedulingProperties schedulingProperties;
    @Autowired private CiProperties ciProperties;
    @Autowired private WebhookProperties webhookProperties;

    @Test
    void queueDepthGauge_isRegisteredPerPriority_withNoOrgTag() {
        var gauges = meterRegistry.find("qualityops.queue.depth").gauges();

        assertThat(gauges).hasSize(3);
        assertThat(gauges).allSatisfy(g -> {
            assertThat(g.getId().getTag("priority")).isIn("HIGH", "NORMAL", "LOW");
            assertThat(g.getId().getTag("org")).isNull();
        });
    }

    @Test
    void dispatchAndCancellationMeters_areRegistered() {
        queueMetrics.recordDispatch(Instant.now().minusSeconds(1));
        queueMetrics.cancellation("queued");
        queueMetrics.scheduleFire("fired");

        assertThat(meterRegistry.find("qualityops.queue.wait_seconds").timer()).isNotNull();
        assertThat(meterRegistry.find("qualityops.queue.dispatch_throughput").counter()).isNotNull();
        assertThat(meterRegistry.find("qualityops.queue.cancellations").counter()).isNotNull();
        assertThat(meterRegistry.find("qualityops.schedule.fires").counter()).isNotNull();
        assertThat(meterRegistry.find("qualityops.queue.active_runs").gauge()).isNotNull();
    }

    @Test
    void schedulingProperties_bindFromYamlIncludingDurationShorthand() {
        assertThat(schedulingProperties.queue().agingStep().toSeconds()).isEqualTo(60);
        assertThat(schedulingProperties.queue().retention().toDays()).isEqualTo(90);
        assertThat(schedulingProperties.fireLedgerRetention().toDays()).isEqualTo(30);
        assertThat(schedulingProperties.queue().sendTimeout().toSeconds()).isEqualTo(10);
        assertThat(schedulingProperties.queue().maxActiveRunsPerOrg()).isEqualTo(5);
    }

    @Test
    void reaperRetryWebhookMeters_areRegistered_withNoOrgTag() {
        queueMetrics.reaped("redispatched");
        queueMetrics.retries("enqueued");
        queueMetrics.webhookDelivery("delivered");

        var reaped = meterRegistry.find("qualityops.queue.reaped").counter();
        var retries = meterRegistry.find("qualityops.queue.retries").counter();
        var webhook = meterRegistry.find("qualityops.webhook.delivery").counter();
        assertThat(reaped).isNotNull();
        assertThat(retries).isNotNull();
        assertThat(webhook).isNotNull();
        assertThat(reaped.getId().getTag("org")).isNull();
        assertThat(retries.getId().getTag("org")).isNull();
        assertThat(webhook.getId().getTag("org")).isNull();
        assertThat(meterRegistry.find("qualityops.scheduling.reaper_duration").timer()).isNotNull();
        assertThat(meterRegistry.find("qualityops.webhook.delivery_duration").timer()).isNotNull();
    }

    @Test
    void leaderGauge_isRegisteredForAllFourJobs_withNoOrgTag() {
        var gauges = meterRegistry.find("qualityops.scheduling.leader").gauges();

        assertThat(gauges).hasSizeGreaterThanOrEqualTo(4);
        assertThat(gauges).extracting(g -> g.getId().getTag("job"))
            .contains("scheduling-tick", "queue-dispatch", "stuck-run-reaper", "webhook-dispatch");
        assertThat(gauges).allSatisfy(g -> assertThat(g.getId().getTag("org")).isNull());
    }

    @Test
    void newProperties_bindFromYaml() {
        assertThat(schedulingProperties.reaper().runTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(schedulingProperties.reaper().dispatchGrace()).isEqualTo(Duration.ofMinutes(2));
        assertThat(schedulingProperties.retry().nonRetryableReasonPrefixes()).contains("run cancelled");
        assertThat(ciProperties.idempotencyRetention().toDays()).isEqualTo(7);
        assertThat(webhookProperties.maxAttempts()).isEqualTo(6);
        assertThat(webhookProperties.dispatchInterval()).isEqualTo(Duration.ofSeconds(10));
    }
}
