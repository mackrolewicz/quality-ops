package com.qualityops.api.scheduling.application.scheduler;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.scheduling.application.port.out.ScheduleRepository;
import com.qualityops.api.scheduling.application.service.ScheduleFireService;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qualityops.scheduling.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleTickJob {

    public static final String LOCK_NAME = "scheduling-tick";

    private static final Logger log = LoggerFactory.getLogger(ScheduleTickJob.class);

    private final ScheduleRepository scheduleRepository;
    private final ScheduleFireService scheduleFireService;
    private final SchedulingProperties props;
    private final QueueMetrics metrics;

    public ScheduleTickJob(ScheduleRepository scheduleRepository,
                           ScheduleFireService scheduleFireService,
                           SchedulingProperties props,
                           QueueMetrics metrics) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleFireService = scheduleFireService;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${qualityops.scheduling.tick-interval:PT15S}")
    @SchedulerLock(name = ScheduleTickJob.LOCK_NAME, lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void tick() {
        var sample = Timer.start();
        try {
            metrics.leaderHeld(LOCK_NAME, true);
            var due = scheduleRepository.findDue(props.tickBatchSize());
            for (var schedule : due) {
                try {
                    scheduleFireService.fire(schedule);
                } catch (RuntimeException e) {
                    log.error("Schedule {} fire failed — continuing with the batch", schedule.id(), e);
                }
            }
        } finally {
            sample.stop(metrics.tickDuration());
            metrics.leaderHeld(LOCK_NAME, false);
        }
    }
}
