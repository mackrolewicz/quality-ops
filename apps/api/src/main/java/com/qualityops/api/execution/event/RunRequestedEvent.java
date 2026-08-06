package com.qualityops.api.execution.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a run is persisted as PENDING. Consumed by the execution
 * module's own Kafka consumer to simulate/orchestrate the run.
 */
public record RunRequestedEvent(
    UUID runId,
    UUID orgId,
    UUID projectId,
    UUID suiteId,
    UUID environmentId,
    UUID triggeredBy,
    Instant requestedAt
) {}
