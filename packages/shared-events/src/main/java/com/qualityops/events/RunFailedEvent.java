package com.qualityops.events;

import java.time.Instant;
import java.util.UUID;

/** Worker -> API. "Execution itself errored" — interrupt / infra / harness
 *  failure, NOT a test failure. Drives the run to FAILED; no results are
 *  generated. Topic: runs.failed. Key: runId. */
public record RunFailedEvent(
        UUID eventId,
        UUID correlationId,
        UUID orgId,
        UUID runId,
        UUID executionId,
        Instant occurredAt,
        int schemaVersion,
        // ---- payload ----
        String reason
) implements RunEvent {
    public static final int SCHEMA_VERSION = 1;
}
