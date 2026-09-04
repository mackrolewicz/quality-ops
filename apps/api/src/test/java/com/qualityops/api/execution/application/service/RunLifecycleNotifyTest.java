package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.in.RetryRunUseCase;
import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.config.DashboardCacheInvalidator;
import com.qualityops.api.webhook.application.port.in.EnqueueRunWebhooksUseCase;
import com.qualityops.api.webhook.domain.WebhookEventType;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP5 (ADR-008 §5): the WebSocket run-progress push is best-effort and must
 * never fail the {@code api-execution} consumer transaction, and must run last —
 * after the queue terminal, webhook enqueue and cache eviction.
 */
@ExtendWith(MockitoExtension.class)
class RunLifecycleNotifyTest {

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
        service = new RunLifecycleService(runRepository, runQueueRepository, retryRunUseCase,
            enqueueRunWebhooksUseCase, dashboardCacheInvalidator, runProgressNotifier,
            repositoryRunWriteUseCase);
    }

    @Test
    void onRunCompleted_notifierThrows_transitionAndHooksStillComplete_inOrder() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(runProgressNotifier).publish(any());

        assertThatNoException().isThrownBy(() -> service.onRunCompleted(completedEvent()));

        InOrder order = inOrder(runRepository, runQueueRepository, enqueueRunWebhooksUseCase,
            dashboardCacheInvalidator, runProgressNotifier);
        order.verify(runRepository).transitionToTerminal(any(), any(), any(), any(), any());
        order.verify(runQueueRepository).transitionQueueState(eq(runId), eq(orgId), any(),
            eq(QueueState.COMPLETED), eq(true));
        order.verify(enqueueRunWebhooksUseCase)
            .enqueueForTerminalRun(runId, orgId, WebhookEventType.RUN_COMPLETED);
        order.verify(dashboardCacheInvalidator).evictForOrg(orgId);
        order.verify(runProgressNotifier).publish(any());
    }

    @Test
    void onRunCompleted_notMoved_doesNotNotify() {
        when(runRepository.transitionToTerminal(any(), any(), any(), any(), any())).thenReturn(false);

        service.onRunCompleted(completedEvent());

        verify(runProgressNotifier, never()).publish(any());
    }

    private RunCompletedEvent completedEvent() {
        return new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
            RunOutcome.PASSED, List.of(), null);
    }
}
