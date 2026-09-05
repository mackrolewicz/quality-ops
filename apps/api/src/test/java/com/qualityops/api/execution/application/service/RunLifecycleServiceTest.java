package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.in.RetryRunUseCase;
import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.config.DashboardCacheInvalidator;
import com.qualityops.api.webhook.application.port.in.EnqueueRunWebhooksUseCase;
import com.qualityops.api.webhook.domain.WebhookEventType;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.RunStartedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunLifecycleServiceTest {

    @Mock
    private RunRepository runRepository;

    @Mock
    private RunQueueRepository runQueueRepository;

    @Mock
    private RetryRunUseCase retryRunUseCase;

    @Mock
    private EnqueueRunWebhooksUseCase enqueueRunWebhooksUseCase;

    @Mock
    private DashboardCacheInvalidator dashboardCacheInvalidator;

    @Mock
    private RunProgressNotifier runProgressNotifier;

    @Mock
    private RepositoryRunWriteUseCase repositoryRunWriteUseCase;

    private RunLifecycleService service;

    private final UUID runId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunLifecycleService(runRepository, runQueueRepository,
            retryRunUseCase, enqueueRunWebhooksUseCase, dashboardCacheInvalidator,
            runProgressNotifier, repositoryRunWriteUseCase);
    }

    @Test
    void onRunStarted_moved_advancesQueueDispatchedToRunning() {
        when(runRepository.transitionStatus(any(), any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunStarted(startedEvent());

        verify(runQueueRepository).transitionQueueState(runId, orgId,
            EnumSet.of(QueueState.DISPATCHED), QueueState.RUNNING, false);
    }

    @Test
    void onRunStarted_notMoved_doesNotTouchQueue() {
        when(runRepository.transitionStatus(any(), any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunStarted(startedEvent());

        verify(runQueueRepository, never())
            .transitionQueueState(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void onRunCompleted_moved_advancesQueueToCompletedTerminal() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(runQueueRepository).transitionQueueState(runId, orgId,
            EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.COMPLETED, true);
    }

    @Test
    void onRunFailed_moved_advancesQueueToFailedTerminal() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunFailed(failedEvent());

        verify(runQueueRepository).transitionQueueState(runId, orgId,
            EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.FAILED, true);
    }

    @Test
    void onRunStarted_pendingRun_transitionsToRunning() {
        var event = startedEvent();
        when(runRepository.transitionStatus(
                eq(runId), eq(orgId), eq(executionId), eq(RunStatus.PENDING), eq(RunStatus.RUNNING),
                any(Instant.class)))
            .thenReturn(true);

        service.onRunStarted(event);

        verify(runRepository).transitionStatus(runId, orgId, executionId,
            RunStatus.PENDING, RunStatus.RUNNING, event.occurredAt());
    }

    @Test
    void onRunStarted_conditionalUpdateMissed_isNoOp() {
        when(runRepository.transitionStatus(any(), any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunStarted(startedEvent());

        verify(runRepository, never()).transitionToTerminal(any(), any(), any(), any(), any());
    }

    @Test
    void onRunStarted_foreignOrgId_isNoOp() {
        when(runRepository.transitionStatus(any(), any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunStarted(startedEvent());

        verify(runRepository, never()).transitionToTerminal(any(), any(), any(), any(), any());
    }

    @Test
    void onRunCompleted_outcomePassed_transitionsToPassed() {
        var event = completedEvent(RunOutcome.PASSED);

        service.onRunCompleted(event);

        verify(runRepository).transitionToTerminal(runId, orgId, executionId,
            RunStatus.PASSED, event.occurredAt());
    }

    @Test
    void onRunCompleted_outcomeFailed_transitionsToFailed() {
        var event = completedEvent(RunOutcome.FAILED);

        service.onRunCompleted(event);

        verify(runRepository).transitionToTerminal(runId, orgId, executionId,
            RunStatus.FAILED, event.occurredAt());
    }

    @Test
    void onRunCompleted_runStillPending_stillTransitions() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(runRepository).transitionToTerminal(eq(runId), eq(orgId), eq(executionId),
            eq(RunStatus.PASSED), any(Instant.class));
        verify(runRepository, never()).transitionStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onRunCompleted_alreadyTerminal_isNoOp() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(runRepository).transitionToTerminal(eq(runId), eq(orgId), eq(executionId),
            eq(RunStatus.PASSED), any(Instant.class));
    }

    @Test
    void onRunCompleted_foreignOrgId_isNoOp() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(runRepository).transitionToTerminal(eq(runId), eq(orgId), eq(executionId),
            eq(RunStatus.PASSED), any(Instant.class));
    }

    @Test
    void onRunCompleted_staleExecutionId_isNoOp() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(runRepository).transitionToTerminal(eq(runId), eq(orgId), eq(executionId),
            eq(RunStatus.PASSED), any(Instant.class));
    }

    @Test
    void onRunFailed_transitionsToFailed() {
        var event = failedEvent();

        service.onRunFailed(event);

        verify(runRepository).transitionToTerminal(runId, orgId, executionId,
            RunStatus.FAILED, event.occurredAt());
    }

    @Test
    void onRunFailed_alreadyTerminal_isNoOp() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunFailed(failedEvent());

        verify(runRepository).transitionToTerminal(eq(runId), eq(orgId), eq(executionId),
            eq(RunStatus.FAILED), any(Instant.class));
    }

    @Test
    void onRunFailed_moved_callsRetryBeforeQueueTerminal_thenEnqueuesWebhook() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunFailed(failedEvent());

        InOrder order = inOrder(retryRunUseCase, runQueueRepository, enqueueRunWebhooksUseCase);
        order.verify(retryRunUseCase).retryIfEligible(runId, orgId, "infra error");
        order.verify(runQueueRepository).transitionQueueState(runId, orgId,
            EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.FAILED, true);
        order.verify(enqueueRunWebhooksUseCase)
            .enqueueForTerminalRun(runId, orgId, WebhookEventType.RUN_FAILED);
    }

    @Test
    void onRunFailed_moved_retryEnqueued_suppressesFailedWebhook() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);
        when(retryRunUseCase.retryIfEligible(runId, orgId, "infra error")).thenReturn(
            java.util.Optional.of(new com.qualityops.api.execution.application.port.in.EnqueueRunUseCase
                .EnqueueRunResult(UUID.randomUUID(), UUID.randomUUID(), QueueState.QUEUED)));

        service.onRunFailed(failedEvent());

        verify(runQueueRepository).transitionQueueState(runId, orgId,
            EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.FAILED, true);
        verify(enqueueRunWebhooksUseCase, never()).enqueueForTerminalRun(any(), any(), any());
    }

    @Test
    void onRunFailed_notMoved_noRetryNoWebhook() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunFailed(failedEvent());

        verify(retryRunUseCase, never()).retryIfEligible(any(), any(), any());
        verify(enqueueRunWebhooksUseCase, never()).enqueueForTerminalRun(any(), any(), any());
    }

    @Test
    void onRunCompleted_moved_enqueuesWebhookNoRetry() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(enqueueRunWebhooksUseCase)
            .enqueueForTerminalRun(runId, orgId, WebhookEventType.RUN_COMPLETED);
        verify(retryRunUseCase, never()).retryIfEligible(any(), any(), any());
    }

    @Test
    void onRunCompleted_notMoved_noWebhook() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(enqueueRunWebhooksUseCase, never()).enqueueForTerminalRun(any(), any(), any());
    }

    @Test
    void onRunCompleted_moved_evictsDashboardCacheForOrg() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(dashboardCacheInvalidator).evictForOrg(orgId);
    }

    @Test
    void onRunCompleted_notMoved_doesNotEvictDashboardCache() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunCompleted(completedEvent(RunOutcome.PASSED));

        verify(dashboardCacheInvalidator, never()).evictForOrg(any());
    }

    @Test
    void onRunFailed_moved_evictsDashboardCacheForOrg() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRunFailed(failedEvent());

        verify(dashboardCacheInvalidator).evictForOrg(orgId);
    }

    @Test
    void onRunFailed_moved_cacheEvictThrows_doesNotPropagate() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(dashboardCacheInvalidator).evictForOrg(any());

        assertThatNoException().isThrownBy(() -> service.onRunFailed(failedEvent()));
        verify(dashboardCacheInvalidator).evictForOrg(orgId);
    }

    private RunStartedEvent startedEvent() {
        return new RunStartedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunStartedEvent.SCHEMA_VERSION);
    }

    private RunCompletedEvent completedEvent(RunOutcome outcome) {
        return new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), outcome,
            List.of(), null);
    }

    private RunFailedEvent failedEvent() {
        return new RunFailedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunFailedEvent.SCHEMA_VERSION, "infra error");
    }
}
