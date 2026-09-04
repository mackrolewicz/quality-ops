package com.qualityops.api.execution.application.scheduler;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.execution.application.service.StuckRunReaperService;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** ADR-007 §1.1 — ShedLock-locked reaper wrapper. Gated on {@code jobs-enabled};
 *  ITs call {@code StuckRunReaperService.sweep()} directly. */
@Component
@ConditionalOnProperty(name = "qualityops.scheduling.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class StuckRunReaper {

    public static final String LOCK_NAME = "stuck-run-reaper";

    private final StuckRunReaperService service;
    private final QueueMetrics metrics;

    public StuckRunReaper(StuckRunReaperService service, QueueMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${qualityops.scheduling.reaper.interval:PT60S}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void reap() {
        metrics.leaderHeld(LOCK_NAME, true);
        var sample = Timer.start();
        try {
            service.sweep();
        } finally {
            sample.stop(metrics.reaperDuration());
            metrics.leaderHeld(LOCK_NAME, false);
        }
    }
}
