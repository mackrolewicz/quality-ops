package com.qualityops.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Worker -> API. "One case finished." Published once per case on topic
 *  {@code results.chunk} (key = runId) after that case's in-run retries and
 *  artifact uploads are attempted. A latency optimisation for the dashboard —
 *  {@link RunCompletedEvent} remains the authoritative fallback. Deliberately
 *  outside the {@link RunEvent} seal: it is a fact about a case, not a run
 *  transition, and is consumed by one dedicated listener, never dispatched
 *  polymorphically.
 *  <p>v2 (ADR-009): gains {@code repositoryItems} + {@code repositoryProvenance}
 *  for repository runs; wire-compatible with v1 (missing fields deserialise as
 *  empty/null). */
public record ResultChunkEvent(
        UUID eventId,
        UUID correlationId,
        UUID orgId,
        UUID runId,
        UUID executionId,
        Instant occurredAt,
        int schemaVersion,
        // ---- payload ----
        UUID testCaseId,
        int attemptEpoch,
        CaseResultSummary.Verdict verdict,
        long durationMillis,
        String firstFailureReason,          // nullable, pre-redacted by the Worker
        List<ArtifactReference> artifacts,          // never null; may be empty
        List<RepositoryTestItem> repositoryItems,   // never null; may be empty (non-repo case)
        RepositoryRunProvenance repositoryProvenance // nullable ⇒ not a repository case
) {
    public static final int SCHEMA_VERSION = 2;

    /** Normalise missing list fields to empty — never null on the wire. */
    public ResultChunkEvent {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        repositoryItems = repositoryItems == null ? List.of() : List.copyOf(repositoryItems);
    }

    /** v1 convenience — no repository payload. Keeps every pre-ADR-009 call site compiling. */
    public ResultChunkEvent(UUID eventId, UUID correlationId, UUID orgId, UUID runId, UUID executionId,
                            Instant occurredAt, int schemaVersion, UUID testCaseId, int attemptEpoch,
                            CaseResultSummary.Verdict verdict, long durationMillis, String firstFailureReason,
                            List<ArtifactReference> artifacts) {
        this(eventId, correlationId, orgId, runId, executionId, occurredAt, schemaVersion, testCaseId,
            attemptEpoch, verdict, durationMillis, firstFailureReason, artifacts, List.of(), null);
    }
}
