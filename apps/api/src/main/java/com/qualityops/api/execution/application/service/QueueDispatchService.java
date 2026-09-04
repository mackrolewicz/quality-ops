package com.qualityops.api.execution.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.out.OrgConcurrencyRepository;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.DispatchCandidate;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.exception.RunEventPublishException;
import com.qualityops.events.RunRequestedEvent;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NOT @Transactional at the method level: the claim UPDATE must COMMIT before
 * the synchronous Kafka publish so a concurrent cancel in the window sees
 * DISPATCHED (cooperative path), never QUEUED. The claim + publish path is never
 * wrapped in a transaction. Once the publish has FAILED there is no ordering
 * constraint left, so the run_queue terminal/rollback write and the matching
 * {@link RunRepository} test_runs PENDING -> terminal reconciliation are done
 * atomically in a single {@link TransactionTemplate} unit.
 * <p>{@link #publishClaimed} is package-private so the Phase 2D stuck-run reaper
 * (same package) reuses the claim-publish-reconcile dance, differing only in
 * whether it has already re-claimed the row (ADR-007 §1.2).
 * Serialised across replicas by the "queue-dispatch" ShedLock lock.
 */
@Service
public class QueueDispatchService {

    private static final Logger log = LoggerFactory.getLogger(QueueDispatchService.class);

    private final RunQueueRepository runQueueRepository;
    private final OrgConcurrencyRepository orgConcurrencyRepository;
    private final RunEventPublisher runEventPublisher;
    private final SchedulingProperties props;
    private final QueueMetrics metrics;
    private final ObjectMapper objectMapper;
    private final RunRepository runRepository;
    private final TransactionTemplate txTemplate;

    public QueueDispatchService(RunQueueRepository runQueueRepository,
                                OrgConcurrencyRepository orgConcurrencyRepository,
                                RunEventPublisher runEventPublisher,
                                SchedulingProperties props,
                                QueueMetrics metrics,
                                ObjectMapper objectMapper,
                                RunRepository runRepository,
                                PlatformTransactionManager transactionManager) {
        this.runQueueRepository = runQueueRepository;
        this.orgConcurrencyRepository = orgConcurrencyRepository;
        this.runEventPublisher = runEventPublisher;
        this.props = props;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.runRepository = runRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** Returns the number of runs dispatched this tick. Also invoked directly by ITs. */
    public int dispatchAvailable() {
        var sample = Timer.start();
        try {
            var q = props.queue();
            Map<UUID, Integer> active = runQueueRepository.countActivePerOrg();
            Map<UUID, Integer> overrides = orgConcurrencyRepository.findAllOverrides();
            int defaultLimit = q.maxActiveRunsPerOrg();

            List<DispatchCandidate> candidates = runQueueRepository.selectQueuedCandidates(
                q.dispatchBatchSize(), (int) q.agingStep().toSeconds(), q.agingMaxBoost());

            Map<UUID, Integer> dispatchedThisTick = new HashMap<>();
            int dispatched = 0;
            for (DispatchCandidate c : candidates) {
                int limit = overrides.getOrDefault(c.orgId(), defaultLimit);
                int used = active.getOrDefault(c.orgId(), 0)
                    + dispatchedThisTick.getOrDefault(c.orgId(), 0);
                if (used >= limit) {
                    continue; // org at capacity — later candidates from other orgs still served
                }
                if (dispatchOne(c)) {
                    dispatchedThisTick.merge(c.orgId(), 1, Integer::sum);
                    dispatched++;
                }
            }
            if (dispatched > 0) {
                log.debug("Dispatched {} run(s) this tick", dispatched);
            }
            return dispatched;
        } finally {
            sample.stop(metrics.dispatchDuration());
        }
    }

    /** Claim (commit) then publish (synchronous). Never both in one tx. */
    private boolean dispatchOne(DispatchCandidate c) {
        return publishClaimed(c, false);
    }

    /**
     * Claim (unless {@code alreadyClaimed}) -> deserialise -> publish -> reconcile,
     * shared by the dispatcher hot path ({@code alreadyClaimed == false}) and the
     * stuck-run reaper ({@code alreadyClaimed == true}, ADR-007 §1.2 — the reaper
     * has already committed its own {@code reclaimStranded} re-claim).
     *
     * @return {@code true} iff {@code runs.requested} was published.
     */
    boolean publishClaimed(DispatchCandidate c, boolean alreadyClaimed) {
        if (!alreadyClaimed && !runQueueRepository.claimForDispatch(c.runId())) {
            return false; // a concurrent cancel or a prior partial dispatch already moved it
        }
        final RunRequestedEvent event;
        try {
            event = objectMapper.readValue(c.requestedEventJson(), RunRequestedEvent.class);
        } catch (JsonProcessingException e) {
            log.error("Corrupt requested_event_json for run {} — marking FAILED", c.runId(), e);
            txTemplate.executeWithoutResult(status -> {
                runQueueRepository.markDispatchFailed(c.runId());
                runRepository.transitionToFailed(c.runId(), c.orgId());
            });
            metrics.dispatchFailed("corrupt_event");
            if (alreadyClaimed) {
                metrics.reaped("reaper_error");
            }
            return false;
        }
        try {
            runEventPublisher.publishRunRequested(event);
        } catch (RunEventPublishException e) {
            return handleFailedPublish(c, e, alreadyClaimed);
        }
        if (alreadyClaimed) {
            metrics.reaped("redispatched");
        } else {
            metrics.recordDispatch(c.enqueuedAt());
        }
        return true;
    }

    private boolean handleFailedPublish(DispatchCandidate c, RunEventPublishException e, boolean alreadyClaimed) {
        boolean ceiling = c.dispatchAttempts() + 1 >= props.queue().dispatchMaxAttempts();
        if (ceiling) {
            txTemplate.executeWithoutResult(status -> {
                runQueueRepository.markDispatchFailed(c.runId());
                runRepository.transitionToFailed(c.runId(), c.orgId());
            });
            metrics.dispatchFailed("attempts_ceiling");
            if (alreadyClaimed) {
                metrics.reaped("redispatch_exhausted");
            }
            log.warn("Run {} hit dispatch-max-attempts ({}) — FAILED",
                c.runId(), props.queue().dispatchMaxAttempts());
            return false;
        }
        if (alreadyClaimed) {
            log.warn("Reaper leaving run {} DISPATCHED for the next pass — send failed (attempt {}): {}",
                c.runId(), c.dispatchAttempts() + 1, e.getMessage());
            return false;
        }
        reconcileAfterFailedPublish(c, e);
        return false;
    }

    /**
     * Dispatcher-only (non-ceiling) rollback branch: the publish already failed, so
     * there is no ordering constraint left — the run_queue rollback write and the
     * matching {@code test_runs} reconciliation are committed atomically in one
     * {@link TransactionTemplate} unit.
     */
    private void reconcileAfterFailedPublish(DispatchCandidate c, RunEventPublishException e) {
        var outcome = new AtomicReference<RunQueueRepository.RollbackOutcome>();
        txTemplate.executeWithoutResult(status -> {
            var result = runQueueRepository.rollbackDispatch(c.runId());
            outcome.set(result);
            if (result == RunQueueRepository.RollbackOutcome.CANCELLED) {
                runRepository.transitionToCancelled(c.runId(), c.orgId());
            }
        });
        if (outcome.get() == RunQueueRepository.RollbackOutcome.CANCELLED) {
            log.info("Send failed for run {} while a cancel was requested — reconciled to CANCELLED",
                c.runId());
        } else {
            log.warn("Send failed for run {} (attempt {}) — rolled back to QUEUED: {}",
                c.runId(), c.dispatchAttempts() + 1, e.getMessage());
        }
    }
}
