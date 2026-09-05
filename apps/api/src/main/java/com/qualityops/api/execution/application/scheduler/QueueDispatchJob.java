package com.qualityops.api.execution.application.scheduler;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qualityops.scheduling.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class QueueDispatchJob {

    public static final String LOCK_NAME = "queue-dispatch";

    private final QueueDispatchService queueDispatchService;
    private final QueueMetrics metrics;

    public QueueDispatchJob(QueueDispatchService queueDispatchService, QueueMetrics metrics) {
        this.queueDispatchService = queueDispatchService;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${qualityops.scheduling.queue.dispatch-interval:PT2S}")
    @SchedulerLock(name = QueueDispatchJob.LOCK_NAME, lockAtMostFor = "PT1M", lockAtLeastFor = "PT2S")
    public void dispatch() {
        metrics.leaderHeld(LOCK_NAME, true);
        try {
            queueDispatchService.dispatchAvailable();
        } finally {
            metrics.leaderHeld(LOCK_NAME, false);
        }
    }
}
