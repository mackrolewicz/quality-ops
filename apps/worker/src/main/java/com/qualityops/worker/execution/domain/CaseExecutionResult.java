package com.qualityops.worker.execution.domain;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public record CaseExecutionResult(
        UUID testCaseId, String name, int orderIndex, CaseStatus status, Duration duration,
        RequestMetadata request, ResponseMetadata response,
        List<AssertionOutcome> assertions, String reason,
        BrowserRunMetadata browser,          // nullable ⇒ non-browser case
        SideEffectClass sideEffectClass,     // worker-internal — never serialised
        int attemptEpoch,                    // 0-based in-run retry attempt that produced this result
        RepoExecutionMetadata repository) {  // nullable ⇒ non-repository case (ADR-009)

    /** Convenience — no repository metadata. Keeps every pre-2F 12-arg call site compiling. */
    public CaseExecutionResult(UUID testCaseId, String name, int orderIndex, CaseStatus status, Duration duration,
                               RequestMetadata request, ResponseMetadata response,
                               List<AssertionOutcome> assertions, String reason, BrowserRunMetadata browser,
                               SideEffectClass sideEffectClass, int attemptEpoch) {
        this(testCaseId, name, orderIndex, status, duration, request, response, assertions, reason, browser,
            sideEffectClass, attemptEpoch, null);
    }

    /** Convenience — no retry context, no repository metadata. Keeps 10-arg call sites compiling. */
    public CaseExecutionResult(UUID testCaseId, String name, int orderIndex, CaseStatus status, Duration duration,
                               RequestMetadata request, ResponseMetadata response,
                               List<AssertionOutcome> assertions, String reason, BrowserRunMetadata browser) {
        this(testCaseId, name, orderIndex, status, duration, request, response, assertions, reason, browser,
            SideEffectClass.NONE_OBSERVED, 0, null);
    }

    /** Copy this result with a different side-effect classification. */
    public CaseExecutionResult withSideEffect(SideEffectClass sec) {
        return new CaseExecutionResult(testCaseId, name, orderIndex, status, duration, request, response,
            assertions, reason, browser, sec, attemptEpoch, repository);
    }

    /** Copy this result stamped with the attempt that produced it. */
    public CaseExecutionResult withAttemptEpoch(int epoch) {
        return new CaseExecutionResult(testCaseId, name, orderIndex, status, duration, request, response,
            assertions, reason, browser, sideEffectClass, epoch, repository);
    }

    /** Copy this result carrying repository run metadata. */
    public CaseExecutionResult withRepository(RepoExecutionMetadata repo) {
        return new CaseExecutionResult(testCaseId, name, orderIndex, status, duration, request, response,
            assertions, reason, browser, sideEffectClass, attemptEpoch, repo);
    }

    public static CaseExecutionResult simulated(UUID id, String name, int idx, CaseStatus status, Duration d) {
        return new CaseExecutionResult(id, name, idx, status, d, null, null, List.of(),
            status == CaseStatus.PASSED ? null : "simulated failure", null,
            SideEffectClass.NONE_OBSERVED, 0, null);
    }
}
