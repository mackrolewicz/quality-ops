package com.qualityops.api.execution.application.service;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;

/**
 * ADR-007 §1. NOT class-{@code @Transactional} (mirrors {@link QueueDispatchService}
 * / {@code RunCancellationService}: the re-claim UPDATE must commit before the
 * re-publish). {@code sweep()} is public — invoked by {@code StuckRunReaper} and
 * directly by ITs.
 * <ul>
 *   <li>(a) stranded DISPATCHED (grace &lt; age &lt; timeout) -> re-publish
 *       {@code runs.requested} via {@link QueueDispatchService#publishClaimed}.</li>
 *   <li>(b) stuck DISPATCHED/RUNNING past run-timeout -> FAILED in both tables,
 *       NO Kafka.</li>
 * </ul>
 * Every WRITE is keyed on the candidate row's own {@code runId} + {@code orgId};
 * the selections are unfiltered platform scans.
 */
@Service
public class StuckRunReaperService {

    private static final Logger log = LoggerFactory.getLogger(StuckRunReaperService.class);

    private final RunQueueRepository runQueueRepository;
    private final RunRepository runRepository;
    private final QueueDispatchService queueDispatchService;
    private final QueueMetrics metrics;
    private final SchedulingProperties props;
    private final TransactionTemplate txTemplate;

    public StuckRunReaperService(RunQueueRepository runQueueRepository,
                                 RunRepository runRepository,
                                 QueueDispatchService queueDispatchService,
                                 QueueMetrics metrics,
                                 SchedulingProperties props,
                                 PlatformTransactionManager transactionManager) {
        this.runQueueRepository = runQueueRepository;
        this.runRepository = runRepository;
        this.queueDispatchService = queueDispatchService;
        this.metrics = metrics;
        this.props = props;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    public void sweep() {
        var now = Instant.now();
        var grace = now.minus(props.reaper().dispatchGrace());
        var timeout = now.minus(props.reaper().runTimeout());
        int batch = props.reaper().batchSize();
        reconcileStranded(grace, timeout, batch);
        reconcileStuck(timeout, batch);
    }

    /** (a) stranded DISPATCHED -> re-publish runs.requested (idempotent). */
    private void reconcileStranded(Instant grace, Instant timeout, int batch) {
        for (var c : runQueueRepository.selectStrandedDispatched(grace, timeout, batch)) {
            if (runQueueRepository.reclaimStranded(c.runId(), grace)) {
                // its own committed tx (adapter @Transactional); publish is outside any tx
                queueDispatchService.publishClaimed(c, true);
                continue;
            }
            // reclaim missed. Two atomic, grace-guarded branches tell the causes apart:
            //  - a still-stale DISPATCHED row whose cancel was requested in the window
            //    -> CANCELLED (ADR-007 §1.2 step 5);
            //  - anything else (a concurrent legitimate dispatch re-claimed it with a
            //    fresh dispatched_at, a real lifecycle transition landed) matches
            //    neither guarded UPDATE -> leave it alone.
            if (runQueueRepository.reclaimStrandedCancel(c.runId(), grace)) {
                runRepository.transitionToCancelled(c.runId(), c.orgId());
                metrics.reaped("cancel_reconciled");
                log.info("Reaper reconciled stranded run {} to CANCELLED (cancel raced)", c.runId());
            }
        }
    }

    /** (b) stuck active run past run-timeout -> FAILED in both tables, no Kafka. */
    private void reconcileStuck(Instant timeout, int batch) {
        for (var s : runQueueRepository.selectStuckActive(timeout, batch)) {
            boolean moved = Boolean.TRUE.equals(txTemplate.execute(status -> {
                int rows = runRepository.reapToFailed(s.runId(), s.orgId(), Instant.now());
                if (rows > 0) {
                    runQueueRepository.transitionQueueState(s.runId(), s.orgId(),
                        EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.FAILED, true);
                }
                return rows > 0;
            }));
            if (moved) {
                metrics.reaped("stuck_failed");
                log.warn("Reaper drove stuck run {} to FAILED (no lifecycle progress past run-timeout)",
                    s.runId());
            }
        }
    }
}
