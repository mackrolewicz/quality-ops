package com.qualityops.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Worker -> API. "Execution finished with a terminal TEST outcome."
 *  outcome is PASSED or FAILED. Re-carries the frozen snapshot so the result
 *  module generates one row per case with no DB read. Topic: runs.completed.
 *  Key: runId.
 *  <p>v4 (ADR-005): nested {@link CaseResultSummary} gains {@code attemptEpoch}
 *  and {@code artifacts} so the terminal alone reconciles {@code test_results}
 *  and {@code test_result_artifacts} when every {@link ResultChunkEvent} is
 *  lost; wire-compatible with v1–v3.
 *  <p>v5 (ADR-009): nested {@link CaseResultSummary} gains {@code repositoryItems}
 *  and {@code repositoryProvenance} so the terminal alone reconstructs
 *  {@code repository_test_item} rows and the {@code repository_run} telemetry;
 *  wire-compatible with v1–v4. */
public record RunCompletedEvent(
        UUID eventId,
        UUID correlationId,
        UUID orgId,
        UUID runId,
        UUID executionId,
        Instant occurredAt,
        int schemaVersion,
        // ---- payload ----
        UUID projectId,
        UUID suiteId,
        RunOutcome outcome,
        List<TestCaseSnapshotItem> testCases,
        List<CaseResultSummary> caseResults /* nullable ⇒ legacy fabrication path */
) implements RunEvent {
    public static final int SCHEMA_VERSION = 5;
}
