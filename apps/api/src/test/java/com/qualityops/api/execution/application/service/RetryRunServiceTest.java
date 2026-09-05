package com.qualityops.api.execution.application.service;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunResult;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.QueueRow;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryRunServiceTest {

    @Mock private RunQueueRepository runQueueRepository;
    @Mock private EnqueueRunUseCase enqueueRunUseCase;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueMetrics metrics = new QueueMetrics(registry, org.mockito.Mockito.mock(RunQueueRepository.class));

    private final UUID failedRunId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private RetryRunService service;

    private static SchedulingProperties props(boolean enabled, int maxPerRun, int maxActivePerOrg) {
        return new SchedulingProperties(true, Duration.ofSeconds(15), 200, Duration.ofDays(30),
            new SchedulingProperties.Queue(Duration.ofSeconds(2), 50, 5, Duration.ofSeconds(60),
                20, 5, Duration.ofSeconds(10), Duration.ofDays(90)),
            new SchedulingProperties.Reaper(Duration.ofSeconds(60), Duration.ofMinutes(2),
                Duration.ofMinutes(30), 100),
            new SchedulingProperties.Retry(enabled, maxPerRun, maxActivePerOrg, Duration.ofHours(1),
                List.of("execution cancelled", "run cancelled")));
    }

    private QueueRow row(int retryCount) {
        return new QueueRow(failedRunId, orgId, null, RunPriority.NORMAL, QueueState.FAILED,
            false, "{}", null, retryCount);
    }

    @BeforeEach
    void setUp() {
        service = new RetryRunService(runQueueRepository, enqueueRunUseCase, props(true, 2, 20), metrics);
    }

    @Test
    void retryIfEligible_cancellationReason_notRetryable() {
        var result = service.retryIfEligible(failedRunId, orgId, "run cancelled before start");

        assertThat(result).isEmpty();
        verify(enqueueRunUseCase, never()).enqueueRetry(any(), any());
        assertThat(registry.counter("qualityops.queue.retries", "outcome", "not_retryable").count())
            .isEqualTo(1.0);
    }

    @Test
    void retryIfEligible_transientReason_withinBudget_enqueuesRetry() {
        when(runQueueRepository.findByRunIdAndOrgId(failedRunId, orgId)).thenReturn(Optional.of(row(0)));
        when(runQueueRepository.countRecentRetriesForOrg(eq(orgId), any(Instant.class))).thenReturn(0L);
        var newRunId = UUID.randomUUID();
        when(enqueueRunUseCase.enqueueRetry(failedRunId, orgId))
            .thenReturn(new EnqueueRunResult(newRunId, UUID.randomUUID(), QueueState.QUEUED));

        var result = service.retryIfEligible(failedRunId, orgId, "worker interrupted");

        assertThat(result).map(EnqueueRunResult::runId).contains(newRunId);
        verify(enqueueRunUseCase).enqueueRetry(failedRunId, orgId);
        assertThat(registry.counter("qualityops.queue.retries", "outcome", "enqueued").count())
            .isEqualTo(1.0);
    }

    @Test
    void retryIfEligible_perRunBudgetExhausted_noRetry() {
        when(runQueueRepository.findByRunIdAndOrgId(failedRunId, orgId)).thenReturn(Optional.of(row(2)));

        var result = service.retryIfEligible(failedRunId, orgId, "worker interrupted");

        assertThat(result).isEmpty();
        verify(enqueueRunUseCase, never()).enqueueRetry(any(), any());
        assertThat(registry.counter("qualityops.queue.retries", "outcome", "budget_exhausted").count())
            .isEqualTo(1.0);
    }

    @Test
    void retryIfEligible_perOrgWindowFull_noRetry() {
        when(runQueueRepository.findByRunIdAndOrgId(failedRunId, orgId)).thenReturn(Optional.of(row(0)));
        when(runQueueRepository.countRecentRetriesForOrg(eq(orgId), any(Instant.class))).thenReturn(20L);

        var result = service.retryIfEligible(failedRunId, orgId, "worker interrupted");

        assertThat(result).isEmpty();
        verify(enqueueRunUseCase, never()).enqueueRetry(any(), any());
        assertThat(registry.counter("qualityops.queue.retries", "outcome", "budget_exhausted").count())
            .isEqualTo(1.0);
    }

    @Test
    void retryIfEligible_disabled_noRetry() {
        lenient().when(runQueueRepository.countRecentRetriesForOrg(any(), any())).thenReturn(0L);
        var disabled = new RetryRunService(runQueueRepository, enqueueRunUseCase, props(false, 2, 20), metrics);

        var result = disabled.retryIfEligible(failedRunId, orgId, "worker interrupted");

        assertThat(result).isEmpty();
        verify(enqueueRunUseCase, never()).enqueueRetry(any(), any());
    }
}
