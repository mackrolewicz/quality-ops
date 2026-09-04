package com.qualityops.events;

import java.util.List;
import java.util.UUID;

/** Lightweight per-case verdict carried on {@link RunCompletedEvent}.
 *  Counts-level only — no headers, no bodies, no request metadata.
 *  {@code attemptEpoch} is the final in-run retry attempt (0-based);
 *  {@code artifacts} lets the terminal reconcile {@code test_result_artifacts}
 *  even if every {@link ResultChunkEvent} is lost.
 *  <p>v5 (ADR-009): gains {@code repositoryItems} + {@code repositoryProvenance}
 *  so the terminal alone reconstructs {@code repository_test_item} rows and the
 *  {@code repository_run} telemetry when every chunk is lost; wire-compatible
 *  with v1–v4 (missing fields deserialise as empty/null). */
public record CaseResultSummary(
        UUID testCaseId,
        Verdict verdict,
        long durationMillis,
        String firstFailureReason,     // nullable, pre-redacted by the Worker
        int attemptEpoch,
        List<ArtifactReference> artifacts,          // never null; may be empty
        List<RepositoryTestItem> repositoryItems,   // never null; may be empty (non-repo case)
        RepositoryRunProvenance repositoryProvenance // nullable ⇒ not a repository case
) {
    public enum Verdict { PASSED, FAILED, TIMEOUT, BLOCKED, ERROR }

    /** Normalise missing/absent list fields (e.g. v2–v4 JSON) to empty. */
    public CaseResultSummary {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        repositoryItems = repositoryItems == null ? List.of() : List.copyOf(repositoryItems);
    }

    /** v2/v3 convenience — no retries, no artifacts, no repository payload. */
    public CaseResultSummary(UUID testCaseId, Verdict verdict, long durationMillis, String firstFailureReason) {
        this(testCaseId, verdict, durationMillis, firstFailureReason, 0, List.of(), List.of(), null);
    }

    /** v4 convenience — retries + artifacts, no repository payload. */
    public CaseResultSummary(UUID testCaseId, Verdict verdict, long durationMillis, String firstFailureReason,
                             int attemptEpoch, List<ArtifactReference> artifacts) {
        this(testCaseId, verdict, durationMillis, firstFailureReason, attemptEpoch, artifacts, List.of(), null);
    }
}
