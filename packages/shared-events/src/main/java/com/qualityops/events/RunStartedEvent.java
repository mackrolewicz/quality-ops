package com.qualityops.events;

import java.time.Instant;
import java.util.UUID;

/** Worker -> API. "Execution has begun." Drives PENDING -> RUNNING.
 *  Topic: runs.started. Key: runId. */
public record RunStartedEvent(
        UUID eventId,
        UUID correlationId,
        UUID orgId,
        UUID runId,
        UUID executionId,
        Instant occurredAt,
        int schemaVersion
) implements RunEvent {
    public static final int SCHEMA_VERSION = 1;
}
