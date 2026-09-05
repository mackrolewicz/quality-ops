package com.qualityops.api.scheduling.application.scheduler;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.scheduling.application.port.out.ScheduleRepository;
import com.qualityops.api.scheduling.application.service.ScheduleFireService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B6 (ADR-006 amendment §2): the {@code qualityops.scheduling.leader{job=scheduling-tick}}
 * gauge reads 1 while the locked tick body scans and is reset to 0 in the
 * finally block — including when {@code findDue} propagates.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleTickJobTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueMetrics metrics = new QueueMetrics(registry, mock(RunQueueRepository.class));
    private final SchedulingProperties props = new SchedulingProperties(
        true, Duration.ofSeconds(15), 200, Duration.ofDays(30),
        new SchedulingProperties.Queue(Duration.ofSeconds(2), 50, 5, Duration.ofSeconds(60),
            20, 5, Duration.ofSeconds(10), Duration.ofDays(90)),
        new SchedulingProperties.Reaper(Duration.ofSeconds(60), Duration.ofMinutes(2),
            Duration.ofMinutes(30), 100),
        new SchedulingProperties.Retry(true, 2, 20, Duration.ofHours(1),
            List.of("execution cancelled", "run cancelled")));

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ScheduleFireService scheduleFireService;

    private ScheduleTickJob job;

    @BeforeEach
    void setUp() {
        job = new ScheduleTickJob(scheduleRepository, scheduleFireService, props, metrics);
    }

    private double leaderGauge(String jobTag) {
        return registry.get("qualityops.scheduling.leader").tag("job", jobTag).gauge().value();
    }

    @Test
    void tick_whileScanning_leaderGaugeIsOne() {
        when(scheduleRepository.findDue(anyInt())).thenAnswer(inv -> {
            assertThat(leaderGauge("scheduling-tick")).isEqualTo(1.0);
            return List.of();
        });

        job.tick();

        assertThat(leaderGauge("scheduling-tick")).isEqualTo(0.0);
    }

    @Test
    void tick_repositoryThrows_leaderGaugeResetToZero() {
        when(scheduleRepository.findDue(anyInt())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(job::tick).isInstanceOf(RuntimeException.class);

        assertThat(leaderGauge("scheduling-tick")).isEqualTo(0.0);
    }
}
