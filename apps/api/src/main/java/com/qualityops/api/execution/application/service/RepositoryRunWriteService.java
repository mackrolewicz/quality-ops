package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RepositoryRunRepository;
import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.events.RepositoryRunProvenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * ADR-009 §3 gap #2 — the sole {@code repository_run} writer. Not
 * class-{@code @Transactional}: every method issues one guarded statement that
 * participates in the caller's transaction ({@code RunEnqueueService},
 * {@code RunLifecycleService}, {@code ResultService}, {@code RunCancellationService}
 * are all transactional). {@link #markState} / {@link #applyProvenance} are
 * redelivery-safe: guarded on {@code org_id} + {@code test_runs.execution_id} +
 * a state guard, a 0-row outcome is a logged no-op.
 */
@Service
public class RepositoryRunWriteService implements RepositoryRunWriteUseCase {

    private static final Logger log = LoggerFactory.getLogger(RepositoryRunWriteService.class);

    private final RepositoryRunRepository repository;

    public RepositoryRunWriteService(RepositoryRunRepository repository) {
        this.repository = repository;
    }

    @Override
    public void stageFrozen(UUID runId, UUID orgId, RepositoryRunFrozen frozen) {
        repository.insertFrozen(runId, orgId, frozen);
        log.info("Staged repository_run for run {} (connection {}, ref {} -> {}, image {})",
            runId, frozen.repositoryConnectionId(), frozen.requestedRef(), frozen.commitSha(),
            frozen.runnerImageRef());
    }

    @Override
    public void markState(UUID runId, UUID orgId, UUID executionId, RepositoryRunState state) {
        int rows = repository.transitionState(runId, orgId, executionId, allowedFrom(state), state);
        if (rows == 0) {
            log.debug("repository_run markState {} for run {} exec {} matched no row — no-op "
                + "(non-repo run, already past this state, or stale executionId)", state, runId, executionId);
        }
    }

    @Override
    public void applyProvenance(UUID runId, UUID orgId, UUID executionId,
                                RepositoryRunProvenance p, int attemptEpoch) {
        int rows = repository.applyTelemetry(runId, orgId, executionId, p.imageDigest(), p.exitCode(),
            p.itemsTotal(), p.itemsPassed(), p.itemsFailed(), p.itemsSkipped(),
            p.checkoutAt(), p.startedAt(), p.finishedAt());
        if (rows == 0) {
            log.debug("repository_run applyProvenance for run {} exec {} epoch {} matched no row — no-op",
                runId, executionId, attemptEpoch);
        }
    }

    /** Legal predecessors for each target state (ADR-009 §13). */
    private static List<RepositoryRunState> allowedFrom(RepositoryRunState target) {
        return switch (target) {
            case RUNNING -> List.of(RepositoryRunState.PENDING);
            case COMPLETED, FAILED -> List.of(RepositoryRunState.PENDING, RepositoryRunState.RUNNING);
            case CANCELLED -> List.of(RepositoryRunState.PENDING);
            case PENDING -> List.of(); // never a transition target
        };
    }
}
