package com.qualityops.api.webhook.application.scheduler;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.webhook.application.service.WebhookDeliveryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** ADR-007 §6.3 — ShedLock-locked sender. Gated on {@code jobs-enabled}; ITs call
 *  {@code WebhookDeliveryService.dispatchDue()} directly. */
@Component
@ConditionalOnProperty(name = "qualityops.scheduling.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class WebhookDispatchJob {

    public static final String LOCK_NAME = "webhook-dispatch";

    private final WebhookDeliveryService service;
    private final QueueMetrics metrics;

    public WebhookDispatchJob(WebhookDeliveryService service, QueueMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${qualityops.webhook.dispatch-interval:PT10S}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT2S")
    public void dispatch() {
        metrics.leaderHeld(LOCK_NAME, true);
        try {
            service.dispatchDue();
        } finally {
            metrics.leaderHeld(LOCK_NAME, false);
        }
    }
}
