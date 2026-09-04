package com.qualityops.api.result.domain;

import java.time.Instant;
import java.util.UUID;

/** One durable artifact reference owned by the API (authoritative relational
 *  state). {@code storageKey} is null exactly when {@code status == UNAVAILABLE}.
 *  Every query filters by {@code orgId}. */
public record TestResultArtifact(
    UUID id,
    UUID orgId,
    UUID runId,
    UUID testCaseId,
    int attemptEpoch,
    ArtifactType artifactType,
    String storageKey,
    String contentType,
    Long sizeBytes,
    ArtifactAvailability status,
    String unavailableReason,
    Instant createdAt
) {
    /** Inbound (pre-persist) reference — id and createdAt assigned by the store. */
    public static TestResultArtifact inbound(UUID orgId, UUID runId, UUID testCaseId, int attemptEpoch,
                                             ArtifactType artifactType, String storageKey, String contentType,
                                             Long sizeBytes, ArtifactAvailability status, String unavailableReason) {
        return new TestResultArtifact(null, orgId, runId, testCaseId, attemptEpoch, artifactType,
            storageKey, contentType, sizeBytes, status, unavailableReason, null);
    }
}
