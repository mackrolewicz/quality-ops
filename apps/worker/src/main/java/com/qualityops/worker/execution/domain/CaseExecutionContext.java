package com.qualityops.worker.execution.domain;

import com.qualityops.events.TestCaseSnapshotItem;

import java.time.Duration;
import java.util.UUID;

public record CaseExecutionContext(
        UUID runId, UUID orgId, UUID executionId, UUID environmentId,
        TestCaseSnapshotItem testCase, Duration effectiveTimeout, long maxResponseBytes,
        CancellationToken cancellation,
        int attemptEpoch) {

    /** Convenience — first attempt. Keeps 8-arg call sites compiling. */
    public CaseExecutionContext(UUID runId, UUID orgId, UUID executionId, UUID environmentId,
                                TestCaseSnapshotItem testCase, Duration effectiveTimeout, long maxResponseBytes,
                                CancellationToken cancellation) {
        this(runId, orgId, executionId, environmentId, testCase, effectiveTimeout, maxResponseBytes,
            cancellation, 0);
    }

    public CaseExecutionContext withAttemptEpoch(int epoch) {
        return new CaseExecutionContext(runId, orgId, executionId, environmentId, testCase,
            effectiveTimeout, maxResponseBytes, cancellation, epoch);
    }
}
