package com.qualityops.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API -> Worker. "Execute this run." Self-contained: carries the full frozen
 *  test-case snapshot so the Worker never queries a database. Topic:
 *  runs.requested. Key: runId.
 *  <p>v4 (ADR-005): nested {@link HttpHeader} / {@link BrowserStep} gain an
 *  optional {@link SecretRef}; wire-compatible with v1–v3 (missing fields
 *  deserialise as null).
 *  <p>v5 (ADR-009): nested {@link TestCaseSnapshotItem} gains an optional
 *  {@link RepoTestSnapshot} {@code repoTest}; wire-compatible with v1–v4. */
public record RunRequestedEvent(
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
        UUID environmentId,
        UUID triggeredBy,
        List<TestCaseSnapshotItem> testCases
) implements RunEvent {
    public static final int SCHEMA_VERSION = 5;
}
