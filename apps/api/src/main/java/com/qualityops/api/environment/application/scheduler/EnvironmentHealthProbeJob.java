package com.qualityops.api.environment.application.scheduler;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.environment.application.port.in.ProbeEnvironmentsUseCase;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-008 §3 — the fifth leader-elected {@code @Scheduled} job on the ADR-006
 * infrastructure. Gated on {@code jobs-enabled}; ITs call
 * {@link ProbeEnvironmentsUseCase#sweep()} directly.
 */
@Component
@ConditionalOnProperty(name = "qualityops.scheduling.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class EnvironmentHealthProbeJob {

    public static final String LOCK_NAME = "environment-health-probe";

    private final ProbeEnvironmentsUseCase service;
    private final QueueMetrics metrics;

    public EnvironmentHealthProbeJob(ProbeEnvironmentsUseCase service, QueueMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${qualityops.scheduling.environment-health.interval:PT60S}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void probe() {
        metrics.leaderHeld(LOCK_NAME, true);
        var sample = Timer.start();
        try {
            service.sweep();
        } finally {
            sample.stop(metrics.probeDuration());
            metrics.leaderHeld(LOCK_NAME, false);
        }
    }
}
