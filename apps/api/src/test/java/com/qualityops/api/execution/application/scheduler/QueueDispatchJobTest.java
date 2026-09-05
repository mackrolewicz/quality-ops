package com.qualityops.api.execution.application.scheduler;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B6 (ADR-006 amendment §2): the {@code qualityops.scheduling.leader{job=queue-dispatch}}
 * gauge reads 1 for the duration of the locked job body and is reset to 0 in a
 * finally block — even when the service throws.
 */
@ExtendWith(MockitoExtension.class)
class QueueDispatchJobTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueMetrics metrics = new QueueMetrics(registry, mock(RunQueueRepository.class));

    @Mock private QueueDispatchService service;

    private QueueDispatchJob job;

    @BeforeEach
    void setUp() {
        job = new QueueDispatchJob(service, metrics);
    }

    private double leaderGauge() {
        return registry.get("qualityops.scheduling.leader").tag("job", "queue-dispatch").gauge().value();
    }

    @Test
    void dispatch_whileServiceRuns_leaderGaugeIsOne() {
        when(service.dispatchAvailable()).thenAnswer(inv -> {
            assertThat(leaderGauge()).isEqualTo(1.0);
            return 0;
        });

        job.dispatch();

        assertThat(leaderGauge()).isEqualTo(0.0);
    }

    @Test
    void dispatch_serviceThrows_leaderGaugeResetToZero() {
        when(service.dispatchAvailable()).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(job::dispatch).isInstanceOf(RuntimeException.class);

        assertThat(leaderGauge()).isEqualTo(0.0);
    }
}
