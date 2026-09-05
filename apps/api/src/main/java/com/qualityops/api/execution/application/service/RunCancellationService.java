package com.qualityops.api.execution.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.execution.application.port.in.CancelRunUseCase;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.QueueRow;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.api.execution.dto.RunResponse;
import com.qualityops.events.RunCancelRequestedEvent;
import com.qualityops.events.RunRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/** ADR-006 §5.2 (as amended in 2C): a plain read of the run_queue row selects
 *  the branch; correctness comes from the guarded conditional UPDATEs + a
 *  fall-through re-read, not a {@code FOR UPDATE} lock.
 *  QUEUED  -> run_queue + test_runs to CANCELLED atomically (one TransactionTemplate
 *             unit), NO Kafka (the dispatcher's WHERE queue_state='QUEUED'
 *             guarantees it is never picked).
 *  D/R     -> cancel_requested=true (its own committed tx) then publish
 *             RunCancelRequestedEvent AFTER commit (202) — commit-then-publish,
 *             same as QueueDispatchService.
 *  terminal / no row / lost race -> NOT_CANCELLABLE (409).
 *  NOT @Transactional at the class level: the publish must not run before commit. */
@Service
public class RunCancellationService implements CancelRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunCancellationService.class);

    private final RunQueueRepository runQueueRepository;
    private final RunRepository runRepository;
    private final RunEventPublisher runEventPublisher;
    private final RepositoryRunWriteUseCase repositoryRunWriteUseCase;
    private final QueueMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public RunCancellationService(RunQueueRepository runQueueRepository,
                                 RunRepository runRepository,
                                 RunEventPublisher runEventPublisher,
                                 RepositoryRunWriteUseCase repositoryRunWriteUseCase,
                                 QueueMetrics metrics,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager) {
        this.runQueueRepository = runQueueRepository;
        this.runRepository = runRepository;
        this.runEventPublisher = runEventPublisher;
        this.repositoryRunWriteUseCase = repositoryRunWriteUseCase;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CancelResult cancel(UUID runId, UUID orgId) {
        QueueRow row = runQueueRepository.findByRunIdAndOrgId(runId, orgId).orElse(null);
        if (row == null) {
            return CancelResult.notCancellable();
        }
        return switch (row.queueState()) {
            case QUEUED -> cancelQueued(runId, orgId);
            case DISPATCHED, RUNNING -> requestCooperativeCancel(row, orgId);
            case COMPLETED, FAILED, CANCELLED -> CancelResult.notCancellable();
        };
    }

    private CancelResult cancelQueued(UUID runId, UUID orgId) {
        // run_queue QUEUED->CANCELLED and test_runs PENDING->CANCELLED must be
        // one atomic unit (no queue-terminal + run-PENDING split on a crash).
        boolean cancelled = Boolean.TRUE.equals(txTemplate.execute(status -> {
            if (!runQueueRepository.cancelQueued(runId, orgId)) {
                return false;
            }
            runRepository.transitionToCancelled(runId, orgId);
            // ADR-009 §10 — a repository run cancelled while QUEUED never reaches
            // the Worker; drive repository_run to CANCELLED in the same unit.
            // No-op (0 rows) for a non-repository run.
            runRepository.findByIdAndOrgId(runId, orgId).ifPresent(r ->
                repositoryRunWriteUseCase.markState(runId, orgId, r.executionId(),
                    RepositoryRunState.CANCELLED));
            return true;
        }));
        if (!cancelled) {
            // Lost the race with the dispatcher's claim — re-read and, if it is
            // now in flight, fall through to the cooperative path instead of a
            // spurious 409.
            var reloaded = runQueueRepository.findByRunIdAndOrgId(runId, orgId).orElse(null);
            if (reloaded == null) {
                return CancelResult.notCancellable();
            }
            return switch (reloaded.queueState()) {
                case DISPATCHED, RUNNING -> requestCooperativeCancel(reloaded, orgId);
                default -> CancelResult.notCancellable();
            };
        }
        metrics.cancellation("queued");
        var run = runRepository.findByIdAndOrgId(runId, orgId).orElseThrow();
        log.info("Run {} cancelled while QUEUED — never dispatched", runId);
        return new CancelResult(Outcome.CANCELLED_QUEUED,
            RunResponse.from(run, QueueState.CANCELLED, null, Boolean.TRUE));
    }

    private CancelResult requestCooperativeCancel(QueueRow row, UUID orgId) {
        // Single guarded UPDATE in its own committed transaction (the adapter
        // method is @Transactional).
        if (!runQueueRepository.requestCancel(row.runId(), orgId)) {
            // The row left DISPATCHED/RUNNING (genuinely terminal) between the
            // read and here — nothing to cancel, and no event to publish.
            return CancelResult.notCancellable();
        }
        // Publish AFTER the requestCancel commit — commit-then-publish, matching
        // QueueDispatchService and .claude/rules/kafka-events.md.
        var run = runRepository.findByIdAndOrgId(row.runId(), orgId).orElseThrow();
        runEventPublisher.publishRunCancelRequested(new RunCancelRequestedEvent(
            UUID.randomUUID(), correlationIdFrom(row), orgId, row.runId(),
            run.executionId(), Instant.now(), RunCancelRequestedEvent.SCHEMA_VERSION));
        metrics.cancellation("dispatched_running");
        log.info("Cooperative cancel requested for run {} (state {})", row.runId(), row.queueState());
        return new CancelResult(Outcome.CANCEL_REQUESTED,
            RunResponse.from(run, row.queueState(), row.priority(), Boolean.TRUE));
    }

    /** Reuse the frozen event's correlationId for trace continuity when present. */
    private UUID correlationIdFrom(QueueRow row) {
        if (row.requestedEventJson() == null) {
            return UUID.randomUUID();
        }
        try {
            return objectMapper.readValue(row.requestedEventJson(), RunRequestedEvent.class).correlationId();
        } catch (JsonProcessingException e) {
            return UUID.randomUUID();
        }
    }
}
