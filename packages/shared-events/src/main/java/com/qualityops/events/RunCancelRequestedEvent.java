package com.qualityops.events;

import java.time.Instant;
import java.util.UUID;

/** API -> Worker. Imperative "please cancel this in-flight run" command.
 *  Deliberately OUTSIDE {@link RunEvent} (mirrors {@link ResultChunkEvent}):
 *  a cancel is a command, not a past-tense lifecycle transition, and is consumed
 *  by exactly one dedicated listener, never dispatched polymorphically.
 *  Topic: runs.cancel. Key: runId. Best-effort — the only fully-guaranteed
 *  cancellation is a run still QUEUED in the API (which never publishes here). */
public record RunCancelRequestedEvent(
        UUID eventId,
        UUID correlationId,
        UUID orgId,
        UUID runId,
        UUID executionId,
        Instant occurredAt,
        int schemaVersion
) {
    public static final int SCHEMA_VERSION = 1;
}
