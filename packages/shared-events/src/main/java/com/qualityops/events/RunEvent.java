package com.qualityops.events;

import java.time.Instant;
import java.util.UUID;

/** Envelope contract carried by every run lifecycle event. Sealed so a dispatcher
 *  can switch exhaustively. Records cannot inherit components, so each permitted
 *  record repeats the seven envelope fields; this interface pins their contract. */
public sealed interface RunEvent
        permits RunRequestedEvent, RunStartedEvent, RunCompletedEvent, RunFailedEvent {

    /** Unique id for THIS event instance (fresh per publish). */
    UUID eventId();

    /** Correlates every event produced while handling one run trigger. Minted by
     *  the API at trigger time; copied verbatim onto all downstream events. */
    UUID correlationId();

    /** Tenant. Present on every event (kafka-events rule). */
    UUID orgId();

    /** API test_runs primary key. Authoritative run identity and the Kafka
     *  message key on every topic. */
    UUID runId();

    /** Execution ATTEMPT id. Minted by the API on RunRequested; echoed by the
     *  Worker on every lifecycle event. 1:1 with runId in Phase 2A. */
    UUID executionId();

    /** When the fact occurred (producer clock). */
    Instant occurredAt();

    /** Wire schema version for this event type. Starts at 1; bumped only on a
     *  breaking payload change. Additive optional fields do not bump it. */
    int schemaVersion();
}
