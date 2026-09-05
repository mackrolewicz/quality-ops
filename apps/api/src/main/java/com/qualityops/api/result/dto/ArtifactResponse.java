package com.qualityops.api.result.dto;

import java.time.Instant;
import java.util.UUID;

/** One stored artifact plus a freshly minted short-TTL presigned GET URL
 *  ({@code url}/{@code urlExpiresAt} null when the artifact is UNAVAILABLE). */
public record ArtifactResponse(
        UUID id,
        UUID testCaseId,
        int attemptEpoch,
        String artifactType,
        String contentType,
        Long sizeBytes,
        String status,
        String unavailableReason,
        String url,
        Instant urlExpiresAt) {
}
