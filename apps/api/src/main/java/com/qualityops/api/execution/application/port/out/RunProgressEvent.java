package com.qualityops.api.execution.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight run status/progress frame delivered over STOMP to
 * {@code /topic/runs/{runId}} (ADR-008 §5). Carries status/progress only —
 * never a full result body or artifact bytes.
 *
 * <p>{@code type} is {@code "STATUS"} for a run-level transition and
 * {@code "CASE"} for a single completed case.
 */
public record RunProgressEvent(
        UUID runId,
        UUID orgId,
        String type,
        String status,
        String queueState,
        Integer casesTotal,
        Integer casesDone,
        UUID testCaseId,
        String verdict,
        Instant at
) {

    /** A run-level transition frame (PENDING -> RUNNING -> PASSED/FAILED). */
    public static RunProgressEvent status(UUID runId, UUID orgId, String status,
                                          String queueState, Instant at) {
        return new RunProgressEvent(runId, orgId, "STATUS", status, queueState,
            null, null, null, null, at);
    }

    /** A single case-completed frame emitted from the {@code results.chunk} path. */
    public static RunProgressEvent caseDone(UUID runId, UUID orgId, UUID testCaseId,
                                            String verdict, Instant at) {
        return new RunProgressEvent(runId, orgId, "CASE", null, null,
            null, null, testCaseId, verdict, at);
    }
}
