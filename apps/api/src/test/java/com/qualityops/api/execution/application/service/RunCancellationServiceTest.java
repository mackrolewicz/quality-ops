package com.qualityops.api.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.execution.application.port.in.CancelRunUseCase.Outcome;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.QueueRow;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunCancellationServiceTest {

    @Mock private RunQueueRepository runQueueRepository;
    @Mock private RunRepository runRepository;
    @Mock private RunEventPublisher runEventPublisher;
    @Mock private RepositoryRunWriteUseCase repositoryRunWriteUseCase;

    private RunCancellationService service;
    private final QueueMetrics metrics =
        new QueueMetrics(new SimpleMeterRegistry(), org.mockito.Mockito.mock(RunQueueRepository.class));

    private final UUID runId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // A mock tx manager makes TransactionTemplate run the callback inline
        // (getTransaction -> null, commit(null) -> no-op), so the branching
        // logic and verify(...) assertions still exercise the real code path.
        service = new RunCancellationService(runQueueRepository, runRepository, runEventPublisher,
            repositoryRunWriteUseCase, metrics, new ObjectMapper(),
            org.mockito.Mockito.mock(PlatformTransactionManager.class));
    }

    private QueueRow row(QueueState state) {
        return new QueueRow(runId, orgId, null, RunPriority.NORMAL, state, false, null, null, 0);
    }

    private TestRun run(RunStatus status) {
        return new TestRun(runId, orgId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), status, UUID.randomUUID(), new RunConfigSnapshot(List.of()),
            null, null, Instant.now());
    }

    @Test
    void cancel_queued_transitionsToCancelled_andPublishesNothing() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId)).thenReturn(Optional.of(row(QueueState.QUEUED)));
        when(runQueueRepository.cancelQueued(runId, orgId)).thenReturn(true);
        when(runRepository.findByIdAndOrgId(runId, orgId)).thenReturn(Optional.of(run(RunStatus.CANCELLED)));

        var result = service.cancel(runId, orgId);

        assertThat(result.outcome()).isEqualTo(Outcome.CANCELLED_QUEUED);
        verify(runRepository).transitionToCancelled(runId, orgId);
        verify(runEventPublisher, never()).publishRunCancelRequested(any());
    }

    @Test
    void cancel_queuedButLostRaceWithDispatcher_returnsNotCancellable() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId))
            .thenReturn(Optional.of(row(QueueState.QUEUED)))
            .thenReturn(Optional.empty());
        when(runQueueRepository.cancelQueued(runId, orgId)).thenReturn(false);

        var result = service.cancel(runId, orgId);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_CANCELLABLE);
        verify(runEventPublisher, never()).publishRunCancelRequested(any());
    }

    @Test
    void cancel_queuedThenClaimedByDispatcher_fallsThroughToCooperative_returnsCancelRequested() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId))
            .thenReturn(Optional.of(row(QueueState.QUEUED)))
            .thenReturn(Optional.of(row(QueueState.DISPATCHED)));
        when(runQueueRepository.cancelQueued(runId, orgId)).thenReturn(false);
        when(runQueueRepository.requestCancel(runId, orgId)).thenReturn(true);
        when(runRepository.findByIdAndOrgId(runId, orgId)).thenReturn(Optional.of(run(RunStatus.PENDING)));

        var result = service.cancel(runId, orgId);

        assertThat(result.outcome()).isEqualTo(Outcome.CANCEL_REQUESTED);
        verify(runEventPublisher).publishRunCancelRequested(any());
    }

    @Test
    void cancel_queuedThenTerminal_returnsNotCancellable() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId))
            .thenReturn(Optional.of(row(QueueState.QUEUED)))
            .thenReturn(Optional.of(row(QueueState.COMPLETED)));
        when(runQueueRepository.cancelQueued(runId, orgId)).thenReturn(false);

        var result = service.cancel(runId, orgId);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_CANCELLABLE);
        verify(runEventPublisher, never()).publishRunCancelRequested(any());
    }

    @Test
    void cancel_dispatched_setsCancelRequested_andPublishesCancelEvent() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId))
            .thenReturn(Optional.of(row(QueueState.DISPATCHED)));
        when(runQueueRepository.requestCancel(runId, orgId)).thenReturn(true);
        when(runRepository.findByIdAndOrgId(runId, orgId)).thenReturn(Optional.of(run(RunStatus.PENDING)));

        var result = service.cancel(runId, orgId);

        assertThat(result.outcome()).isEqualTo(Outcome.CANCEL_REQUESTED);
        verify(runQueueRepository).requestCancel(runId, orgId);
        verify(runEventPublisher).publishRunCancelRequested(any());
        verify(runRepository, never()).transitionToCancelled(any(), any());
    }

    @Test
    void cancel_running_setsCancelRequested_andPublishesCancelEvent() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId))
            .thenReturn(Optional.of(row(QueueState.RUNNING)));
        when(runQueueRepository.requestCancel(runId, orgId)).thenReturn(true);
        when(runRepository.findByIdAndOrgId(runId, orgId)).thenReturn(Optional.of(run(RunStatus.RUNNING)));

        var result = service.cancel(runId, orgId);

        assertThat(result.outcome()).isEqualTo(Outcome.CANCEL_REQUESTED);
        verify(runEventPublisher).publishRunCancelRequested(any());
    }

    @Test
    void cancel_dispatchedButRunAlreadyTerminal_requestCancelZeroRows_returnsNotCancellable() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId))
            .thenReturn(Optional.of(row(QueueState.DISPATCHED)));
        when(runQueueRepository.requestCancel(runId, orgId)).thenReturn(false);

        var result = service.cancel(runId, orgId);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_CANCELLABLE);
        verify(runEventPublisher, never()).publishRunCancelRequested(any());
    }

    @Test
    void cancel_terminalQueueRow_returnsNotCancellable() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId))
            .thenReturn(Optional.of(row(QueueState.COMPLETED)));

        assertThat(service.cancel(runId, orgId).outcome()).isEqualTo(Outcome.NOT_CANCELLABLE);
    }

    @Test
    void cancel_noQueueRow_returnsNotCancellable() {
        when(runQueueRepository.findByRunIdAndOrgId(runId, orgId)).thenReturn(Optional.empty());

        assertThat(service.cancel(runId, orgId).outcome()).isEqualTo(Outcome.NOT_CANCELLABLE);
    }
}
