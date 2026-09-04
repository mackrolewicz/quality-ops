package com.qualityops.worker.execution.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Durable, cross-restart guard against double-executing an attempt.
 *  Backed by {@code worker.execution_attempt} — a side-effect guard, NOT
 *  authoritative run state. */
public interface ExecutionAttemptStore {

    ClaimResult claim(UUID executionId, UUID runId, UUID orgId, RunnerKind kind);

    /** Extend the lease on a claim this epoch owns. Best effort — never throws for "row gone". */
    void heartbeat(UUID executionId, int epoch);

    /** Record terminal intent + the exact outbound event JSON BEFORE publishing.
     *  @return true iff our epoch still owns the row (false ⇒ stolen ⇒ abort publish). */
    boolean markCompleted(UUID executionId, int epoch, String terminalTopic, String terminalEventJson);

    /** Steal a RUNNING claim whose heartbeat is older than {@code lease}. */
    OptionalInt steal(UUID executionId, Duration lease);

    int deleteOlderThan(Instant cutoff);

    /** ADR-009 §9 (gap #6) — reads {@code worker.execution_attempt} only.
     *  @return true iff a row exists for {@code executionId} in status COMPLETED. */
    boolean isAttemptCompleted(UUID executionId);

    /** ADR-009 §9 (gap #6) — the subset of {@code executionIds} whose
     *  {@code worker.execution_attempt} row is COMPLETED. Used by
     *  {@code RepoContainerSweeper} to compute the "possibly-live" set for
     *  {@code ContainerRunnerPort.sweepOrphans}. */
    Set<UUID> completedExecutionIds(Collection<UUID> executionIds);

    sealed interface ClaimResult permits Claimed, AlreadyRunning, AlreadyCompleted {}
    record Claimed(int epoch) implements ClaimResult {}
    record AlreadyRunning(int epoch, Instant heartbeatAt) implements ClaimResult {}
    record AlreadyCompleted(String terminalTopic, String terminalEventJson) implements ClaimResult {}
}
