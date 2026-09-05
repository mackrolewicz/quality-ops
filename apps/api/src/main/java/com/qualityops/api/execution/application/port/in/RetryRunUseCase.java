package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunResult;

import java.util.Optional;
import java.util.UUID;

/** ADR-007 §2. Called from {@code RunLifecycleService.onRunFailed}, gated on the
 *  {@code moved} boolean, inside the lifecycle handler's transaction. */
public interface RetryRunUseCase {

    /** Enqueues a fresh retry run iff the reason is retryable and both budgets
     *  have room. No-op otherwise. Runs inside the caller's transaction. */
    Optional<EnqueueRunResult> retryIfEligible(UUID failedRunId, UUID orgId, String failureReason);
}
