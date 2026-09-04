package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.events.RepositoryRunProvenance;

import java.util.UUID;

/** ADR-009 §3 gap #2 — the single write path into {@code repository_run}. The
 *  {@code scm} preflight ({@link #stageFrozen}), the run-lifecycle consumer
 *  ({@link #markState}) and the result consumer ({@link #applyProvenance}) all
 *  write through this one port so no new cross-module cycle appears. The API is
 *  the sole writer of authoritative state. */
public interface RepositoryRunWriteUseCase {

    /** Insert the frozen {@code repository_run} row (state PENDING) for a run that
     *  carries a repository test case. Runs in the caller's enqueue transaction —
     *  a failure rolls back {@code test_runs} + {@code run_queue} + this row. */
    void stageFrozen(UUID runId, UUID orgId, RepositoryRunFrozen frozen);

    /** Advance {@code state} from an earlier state, org- + executionId-guarded
     *  (a stale/foreign event is a 0-row no-op). No-op when the run has no
     *  {@code repository_run} row. */
    void markState(UUID runId, UUID orgId, UUID executionId, RepositoryRunState state);

    /** Apply container/report telemetry (digest, exit code, item counts,
     *  checkout/started/finished timestamps, a redacted failure summary) from a
     *  {@code results.chunk} or the v5 terminal, org- + executionId-guarded and
     *  epoch-monotone (a stale/redelivered {@code attemptEpoch} is a no-op).
     *  Timestamps are COALESCEd so an earlier chunk's values are not clobbered.
     *  {@code errorDetail} is null for a clean pass. */
    void applyProvenance(UUID runId, UUID orgId, UUID executionId, RepositoryRunProvenance provenance,
                         int attemptEpoch, String errorDetail);
}
