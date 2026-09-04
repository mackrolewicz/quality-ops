package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.in.RetryRunUseCase;
import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import com.qualityops.api.config.DashboardCacheInvalidator;
import com.qualityops.api.webhook.application.port.in.EnqueueRunWebhooksUseCase;
import com.qualityops.api.webhook.domain.WebhookEventType;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.RunStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Consumes Worker lifecycle facts and applies them as conditional status
 * transitions. All three handlers are idempotent: each test_runs repository call
 * is a guarded UPDATE whose affected-row count of 0 means "already past this
 * state" and is a logged no-op. The run_queue transition (ADR-006 §3.3) is gated
 * on the test_runs UPDATE having moved a row, so it inherits the executionId +
 * org guarantees without a redundant guard column.
 */
@Service
@Transactional
public class RunLifecycleService implements ApplyRunLifecycleUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);

    private final RunRepository runRepository;
    private final RunQueueRepository runQueueRepository;
    private final RetryRunUseCase retryRunUseCase;
    private final EnqueueRunWebhooksUseCase enqueueRunWebhooksUseCase;
    private final DashboardCacheInvalidator dashboardCacheInvalidator;
    private final RunProgressNotifier runProgressNotifier;
    private final RepositoryRunWriteUseCase repositoryRunWriteUseCase;

    // Cycle check: RetryRunService -> EnqueueRunUseCase, RunQueueRepository,
    // QueueMetrics, SchedulingProperties; WebhookDeliveryService -> GetRunUseCase
    // (=RunService), WebhookEndpointRepository, WebhookDeliveryRepository;
    // DashboardCacheInvalidator -> StringRedisTemplate, QueueMetrics, CacheProperties;
    // RunProgressNotifier impl -> StringRedisTemplate, SimpMessagingTemplate, QueueMetrics.
    // Neither RunService nor RunEnqueueService depends on RunLifecycleService — no cycle.
    public RunLifecycleService(RunRepository runRepository,
                               RunQueueRepository runQueueRepository,
                               RetryRunUseCase retryRunUseCase,
                               EnqueueRunWebhooksUseCase enqueueRunWebhooksUseCase,
                               DashboardCacheInvalidator dashboardCacheInvalidator,
                               RunProgressNotifier runProgressNotifier,
                               RepositoryRunWriteUseCase repositoryRunWriteUseCase) {
        this.runRepository = runRepository;
        this.runQueueRepository = runQueueRepository;
        this.retryRunUseCase = retryRunUseCase;
        this.enqueueRunWebhooksUseCase = enqueueRunWebhooksUseCase;
        this.dashboardCacheInvalidator = dashboardCacheInvalidator;
        this.runProgressNotifier = runProgressNotifier;
        this.repositoryRunWriteUseCase = repositoryRunWriteUseCase;
    }

    @Override
    public void onRunStarted(RunStartedEvent event) {
        boolean moved = runRepository.transitionStatus(
            event.runId(), event.orgId(), event.executionId(),
            RunStatus.PENDING, RunStatus.RUNNING, event.occurredAt());
        if (moved) {
            runQueueRepository.transitionQueueState(event.runId(), event.orgId(),
                EnumSet.of(QueueState.DISPATCHED), QueueState.RUNNING, false);
            markRepositoryStateQuietly(event.runId(), event.orgId(), event.executionId(),
                RepositoryRunState.RUNNING);
            notifyQuietly(RunProgressEvent.status(event.runId(), event.orgId(),
                "RUNNING", "RUNNING", event.occurredAt()));
        } else {
            log.info("RunStarted for run {} exec {} matched no PENDING row — no-op "
                + "(duplicate, already terminal, stale executionId, or foreign tenant)",
                event.runId(), event.executionId());
        }
    }

    @Override
    public void onRunCompleted(RunCompletedEvent event) {
        boolean moved = runRepository.transitionToTerminal(
            event.runId(), event.orgId(), event.executionId(),
            toRunStatus(event.outcome()), event.occurredAt());
        if (moved) {
            runQueueRepository.transitionQueueState(event.runId(), event.orgId(),
                EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.COMPLETED, true);
            markRepositoryStateQuietly(event.runId(), event.orgId(), event.executionId(),
                RepositoryRunState.COMPLETED);
            enqueueRunWebhooksUseCase.enqueueForTerminalRun(
                event.runId(), event.orgId(), WebhookEventType.RUN_COMPLETED);
            evictQuietly(event.orgId());
            notifyQuietly(RunProgressEvent.status(event.runId(), event.orgId(),
                toRunStatus(event.outcome()).name(), "COMPLETED", event.occurredAt()));
        } else {
            log.info("RunCompleted for run {} exec {} matched no PENDING/RUNNING row — no-op "
                + "(already terminal, stale executionId, or foreign tenant)",
                event.runId(), event.executionId());
        }
    }

    @Override
    public void onRunFailed(RunFailedEvent event) {
        boolean moved = runRepository.transitionToTerminal(
            event.runId(), event.orgId(), event.executionId(),
            RunStatus.FAILED, event.occurredAt());
        if (moved) {
            // BEFORE the queue terminal write — that call nulls
            // run_queue.requested_event_json which the retry needs verbatim
            // (with correlationId + retry_count). ADR-007 §2.2.
            var retry = retryRunUseCase.retryIfEligible(event.runId(), event.orgId(), event.reason());
            runQueueRepository.transitionQueueState(event.runId(), event.orgId(),
                EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.FAILED, true);
            markRepositoryStateQuietly(event.runId(), event.orgId(), event.executionId(),
                RepositoryRunState.FAILED);
            if (retry.isEmpty()) {
                enqueueRunWebhooksUseCase.enqueueForTerminalRun(
                    event.runId(), event.orgId(), WebhookEventType.RUN_FAILED);
            } else {
                // A retry run is queued — do NOT tell a Caseflow consumer this run
                // "failed" (it would fail a pipeline on an attempt that is about to
                // be re-run). The retry run delivers its own terminal webhook.
                log.info("RunFailed for run {} — retry run {} queued; suppressing run.failed webhook",
                    event.runId(), retry.get().runId());
            }
            evictQuietly(event.orgId());
            notifyQuietly(RunProgressEvent.status(event.runId(), event.orgId(),
                "FAILED", "FAILED", event.occurredAt()));
        } else {
            log.info("RunFailed for run {} exec {} — no-op", event.runId(), event.executionId());
        }
    }

    /** Best-effort {@code repository_run.state} advance (ADR-009 §13). Gated on the
     *  {@code test_runs} transition already having moved a row, so it inherits the
     *  executionId + org guarantees; a non-repository run is a 0-row no-op. Swallow
     *  so the {@code api-execution} consumer transaction is never rolled back. */
    private void markRepositoryStateQuietly(UUID runId, UUID orgId, UUID executionId,
                                            RepositoryRunState state) {
        try {
            repositoryRunWriteUseCase.markState(runId, orgId, executionId, state);
        } catch (RuntimeException e) {
            log.warn("repository_run state advance to {} failed for run {}", state, runId, e);
        }
    }

    /** Best-effort per-org dashboard cache eviction. The invalidator already swallows
     *  its own Redis errors; this is belt-and-braces so the {@code api-execution}
     *  consumer transaction is never rolled back by a cache problem. */
    private void evictQuietly(UUID orgId) {
        try {
            dashboardCacheInvalidator.evictForOrg(orgId);
        } catch (RuntimeException e) {
            log.warn("dashboard cache evict failed for org {}", orgId, e);
        }
    }

    /** Best-effort WebSocket run-progress push (ADR-008 §5). The notifier is
     *  already best-effort; this is belt-and-braces so the {@code api-execution}
     *  consumer transaction is never rolled back by a broker/Redis problem. */
    private void notifyQuietly(RunProgressEvent event) {
        try {
            runProgressNotifier.publish(event);
        } catch (RuntimeException e) {
            log.warn("WS run-progress notify failed for run {}", event.runId(), e);
        }
    }

    private static RunStatus toRunStatus(RunOutcome outcome) {
        return switch (outcome) {
            case PASSED -> RunStatus.PASSED;
            case FAILED -> RunStatus.FAILED;
        };
    }
}
