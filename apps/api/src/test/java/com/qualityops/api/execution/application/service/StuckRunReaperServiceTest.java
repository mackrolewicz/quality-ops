package com.qualityops.api.execution.application.service;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.DispatchCandidate;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.StuckRun;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StuckRunReaperServiceTest {

    @Mock private RunQueueRepository runQueueRepository;
    @Mock private RunRepository runRepository;
    @Mock private QueueDispatchService queueDispatchService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueMetrics metrics = new QueueMetrics(registry, mock(RunQueueRepository.class));

    private final UUID runId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private StuckRunReaperService service;

    private static SchedulingProperties props() {
        return new SchedulingProperties(true, Duration.ofSeconds(15), 200, Duration.ofDays(30),
            new SchedulingProperties.Queue(Duration.ofSeconds(2), 50, 5, Duration.ofSeconds(60),
                20, 5, Duration.ofSeconds(10), Duration.ofDays(90)),
            new SchedulingProperties.Reaper(Duration.ofSeconds(60), Duration.ofMinutes(2),
                Duration.ofMinutes(30), 100),
            new SchedulingProperties.Retry(true, 2, 20, Duration.ofHours(1),
                List.of("execution cancelled", "run cancelled")));
    }

    private DispatchCandidate candidate() {
        return new DispatchCandidate(runId, orgId, RunPriority.NORMAL,
            Instant.now().minusSeconds(200), 1, "{}");
    }

    @BeforeEach
    void setUp() {
        service = new StuckRunReaperService(runQueueRepository, runRepository, queueDispatchService,
            metrics, props(), mock(PlatformTransactionManager.class));
    }

    @Test
    void sweep_noCandidates_writesNothing() {
        when(runQueueRepository.selectStrandedDispatched(any(), any(), anyInt())).thenReturn(List.of());
        when(runQueueRepository.selectStuckActive(any(), anyInt())).thenReturn(List.of());

        service.sweep();

        verifyNoInteractions(queueDispatchService);
        verify(runRepository, never()).reapToFailed(any(), any(), any());
    }

    @Test
    void sweep_strandedCandidate_reclaimsThenRepublishes() {
        var c = candidate();
        when(runQueueRepository.selectStrandedDispatched(any(), any(), anyInt())).thenReturn(List.of(c));
        when(runQueueRepository.selectStuckActive(any(), anyInt())).thenReturn(List.of());
        when(runQueueRepository.reclaimStranded(eq(runId), any())).thenReturn(true);

        service.sweep();

        verify(queueDispatchService).publishClaimed(c, true);
    }

    @Test
    void sweep_reclaimFalse_cancelRaced_reconcilesToCancelled() {
        var c = candidate();
        when(runQueueRepository.selectStrandedDispatched(any(), any(), anyInt())).thenReturn(List.of(c));
        when(runQueueRepository.selectStuckActive(any(), anyInt())).thenReturn(List.of());
        when(runQueueRepository.reclaimStranded(eq(runId), any())).thenReturn(false);
        when(runQueueRepository.reclaimStrandedCancel(eq(runId), any())).thenReturn(true);

        service.sweep();

        verify(runRepository).transitionToCancelled(runId, orgId);
        verify(queueDispatchService, never()).publishClaimed(any(), anyBoolean());
        assertThat(registry.counter("qualityops.queue.reaped", "kind", "cancel_reconciled").count())
            .isEqualTo(1.0);
    }

    @Test
    void sweep_reclaimFalse_concurrentLegitimateDispatch_leavesRowAlone() {
        var c = candidate();
        when(runQueueRepository.selectStrandedDispatched(any(), any(), anyInt())).thenReturn(List.of(c));
        when(runQueueRepository.selectStuckActive(any(), anyInt())).thenReturn(List.of());
        when(runQueueRepository.reclaimStranded(eq(runId), any())).thenReturn(false);
        // neither guarded UPDATE matches — a concurrent dispatch re-claimed it with a fresh dispatched_at
        when(runQueueRepository.reclaimStrandedCancel(eq(runId), any())).thenReturn(false);

        service.sweep();

        verify(runRepository, never()).transitionToCancelled(any(), any());
        verify(queueDispatchService, never()).publishClaimed(any(), anyBoolean());
    }

    @Test
    void sweep_stuckRun_reapReturnsZero_noQueueWrite() {
        when(runQueueRepository.selectStrandedDispatched(any(), any(), anyInt())).thenReturn(List.of());
        when(runQueueRepository.selectStuckActive(any(), anyInt()))
            .thenReturn(List.of(new StuckRun(runId, orgId, QueueState.RUNNING)));
        when(runRepository.reapToFailed(eq(runId), eq(orgId), any())).thenReturn(0);

        service.sweep();

        verify(runQueueRepository, never())
            .transitionQueueState(any(), any(), any(), eq(QueueState.FAILED), eq(true));
    }

    @Test
    void sweep_stuckRun_reapMovesRow_gatedQueueTransitionAndCounter() {
        when(runQueueRepository.selectStrandedDispatched(any(), any(), anyInt())).thenReturn(List.of());
        when(runQueueRepository.selectStuckActive(any(), anyInt()))
            .thenReturn(List.of(new StuckRun(runId, orgId, QueueState.RUNNING)));
        when(runRepository.reapToFailed(eq(runId), eq(orgId), any())).thenReturn(1);

        service.sweep();

        verify(runQueueRepository)
            .transitionQueueState(eq(runId), eq(orgId), any(), eq(QueueState.FAILED), eq(true));
        assertThat(registry.counter("qualityops.queue.reaped", "kind", "stuck_failed").count())
            .isEqualTo(1.0);
    }
}
