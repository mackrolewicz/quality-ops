package com.qualityops.api.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.out.OrgConcurrencyRepository;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.DispatchCandidate;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.exception.RunEventPublishException;
import com.qualityops.events.RunRequestedEvent;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueDispatchServiceTest {

    @Mock private RunQueueRepository runQueueRepository;
    @Mock private OrgConcurrencyRepository orgConcurrencyRepository;
    @Mock private RunEventPublisher runEventPublisher;
    @Mock private RunRepository runRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final QueueMetrics metrics =
        new QueueMetrics(new SimpleMeterRegistry(), org.mockito.Mockito.mock(RunQueueRepository.class));

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    private QueueDispatchService serviceWithCap(int maxPerOrg) {
        var props = new SchedulingProperties(true, Duration.ofSeconds(15), 200, Duration.ofDays(30),
            new SchedulingProperties.Queue(Duration.ofSeconds(2), 50, maxPerOrg, Duration.ofSeconds(60),
                20, 5, Duration.ofSeconds(10), Duration.ofDays(90)),
            new SchedulingProperties.Reaper(Duration.ofSeconds(60), Duration.ofMinutes(2),
                Duration.ofMinutes(30), 100),
            new SchedulingProperties.Retry(true, 2, 20, Duration.ofHours(1),
                List.of("execution cancelled", "run cancelled")));
        return new QueueDispatchService(runQueueRepository, orgConcurrencyRepository,
            runEventPublisher, props, metrics, objectMapper, runRepository,
            mock(PlatformTransactionManager.class));
    }

    @BeforeEach
    void setUp() {
        lenient().when(orgConcurrencyRepository.findAllOverrides()).thenReturn(Map.of());
        lenient().when(runQueueRepository.countActivePerOrg()).thenReturn(Map.of());
    }

    private DispatchCandidate candidate(UUID org, RunPriority p, int attempts) {
        var runId = UUID.randomUUID();
        var event = new RunRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), org, runId,
            UUID.randomUUID(), Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());
        try {
            return new DispatchCandidate(runId, org, p, Instant.now().minusSeconds(10), attempts,
                objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void dispatchAvailable_claimThenPublish_claimsBeforePublishing() {
        var c1 = candidate(orgA, RunPriority.HIGH, 0);
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(c1));
        when(runQueueRepository.claimForDispatch(any())).thenReturn(true);

        int dispatched = serviceWithCap(5).dispatchAvailable();

        assertThat(dispatched).isEqualTo(1);
        var order = inOrder(runQueueRepository, runEventPublisher);
        order.verify(runQueueRepository).claimForDispatch(c1.runId());
        order.verify(runEventPublisher).publishRunRequested(any(RunRequestedEvent.class));
    }

    @Test
    void dispatchAvailable_orgAtCap_skipsRemainingForThatOrg_butServesOtherOrg() {
        var a1 = candidate(orgA, RunPriority.HIGH, 0);
        var a2 = candidate(orgA, RunPriority.HIGH, 0);
        var b1 = candidate(orgB, RunPriority.NORMAL, 0);
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt()))
            .thenReturn(List.of(a1, a2, b1));
        when(runQueueRepository.claimForDispatch(any())).thenReturn(true);

        int dispatched = serviceWithCap(1).dispatchAvailable();

        assertThat(dispatched).isEqualTo(2);
        verify(runQueueRepository).claimForDispatch(a1.runId());
        verify(runQueueRepository, never()).claimForDispatch(a2.runId());
        verify(runQueueRepository).claimForDispatch(b1.runId());
    }

    @Test
    void dispatchAvailable_claimReturnsFalse_doesNotPublish() {
        var c1 = candidate(orgA, RunPriority.NORMAL, 0);
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(c1));
        when(runQueueRepository.claimForDispatch(c1.runId())).thenReturn(false);

        int dispatched = serviceWithCap(5).dispatchAvailable();

        assertThat(dispatched).isZero();
        verify(runEventPublisher, never()).publishRunRequested(any());
    }

    @Test
    void dispatchAvailable_sendFailsBelowCeiling_rollsRowBackToQueued() {
        var c1 = candidate(orgA, RunPriority.NORMAL, 0);
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(c1));
        when(runQueueRepository.claimForDispatch(c1.runId())).thenReturn(true);
        when(runQueueRepository.rollbackDispatch(c1.runId()))
            .thenReturn(RunQueueRepository.RollbackOutcome.REQUEUED);
        doThrow(new RunEventPublishException("boom", new RuntimeException()))
            .when(runEventPublisher).publishRunRequested(any());

        int dispatched = serviceWithCap(5).dispatchAvailable();

        assertThat(dispatched).isZero();
        verify(runQueueRepository).rollbackDispatch(c1.runId());
        verify(runQueueRepository, never()).markDispatchFailed(any());
        verify(runRepository, never()).transitionToFailed(any(), any());
        verify(runRepository, never()).transitionToCancelled(any(), any());
    }

    @Test
    void dispatchAvailable_sendFailsAtCeiling_marksRowFailed() {
        var atCeiling = candidate(orgA, RunPriority.NORMAL, 4); // 4 + 1 == max-attempts 5
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt()))
            .thenReturn(List.of(atCeiling));
        when(runQueueRepository.claimForDispatch(atCeiling.runId())).thenReturn(true);
        doThrow(new RunEventPublishException("boom", new RuntimeException()))
            .when(runEventPublisher).publishRunRequested(any());

        serviceWithCap(5).dispatchAvailable();

        verify(runQueueRepository).markDispatchFailed(atCeiling.runId());
        verify(runQueueRepository, never()).rollbackDispatch(any());
        verify(runRepository).transitionToFailed(atCeiling.runId(), orgA);
    }

    @Test
    void dispatchOne_corruptRequestedEventJson_marksRunAndQueueFailed() {
        var runId = UUID.randomUUID();
        var corrupt = new DispatchCandidate(runId, orgA, RunPriority.NORMAL,
            Instant.now().minusSeconds(10), 0, "[]");
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt()))
            .thenReturn(List.of(corrupt));
        when(runQueueRepository.claimForDispatch(runId)).thenReturn(true);

        int dispatched = serviceWithCap(5).dispatchAvailable();

        assertThat(dispatched).isZero();
        verify(runQueueRepository).markDispatchFailed(runId);
        verify(runRepository).transitionToFailed(runId, orgA);
        verify(runEventPublisher, never()).publishRunRequested(any());
    }

    @Test
    void dispatchOne_sendLostWhileCancelRequested_transitionsRunCancelled() {
        var c1 = candidate(orgA, RunPriority.NORMAL, 0);
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(c1));
        when(runQueueRepository.claimForDispatch(c1.runId())).thenReturn(true);
        when(runQueueRepository.rollbackDispatch(c1.runId()))
            .thenReturn(RunQueueRepository.RollbackOutcome.CANCELLED);
        doThrow(new RunEventPublishException("boom", new RuntimeException()))
            .when(runEventPublisher).publishRunRequested(any());

        serviceWithCap(5).dispatchAvailable();

        verify(runRepository).transitionToCancelled(c1.runId(), orgA);
        verify(runRepository, never()).transitionToFailed(any(), any());
    }

    @Test
    void dispatchOne_sendLostRollbackNoop_touchesNeitherRun() {
        var c1 = candidate(orgA, RunPriority.NORMAL, 0);
        when(runQueueRepository.selectQueuedCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(c1));
        when(runQueueRepository.claimForDispatch(c1.runId())).thenReturn(true);
        when(runQueueRepository.rollbackDispatch(c1.runId()))
            .thenReturn(RunQueueRepository.RollbackOutcome.NOOP);
        doThrow(new RunEventPublishException("boom", new RuntimeException()))
            .when(runEventPublisher).publishRunRequested(any());

        serviceWithCap(5).dispatchAvailable();

        verify(runRepository, never()).transitionToFailed(any(), any());
        verify(runRepository, never()).transitionToCancelled(any(), any());
    }
}
