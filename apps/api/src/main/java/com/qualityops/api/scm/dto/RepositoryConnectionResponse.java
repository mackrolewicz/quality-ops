package com.qualityops.api.scm.dto;

import com.qualityops.api.scm.domain.RepositoryConnection;
import com.qualityops.events.RepositoryProvider;

import java.time.Instant;
import java.util.UUID;

/** ADR-009 §11 — never carries a token; echoes only the opaque
 *  {@code credentialRef}. */
public record RepositoryConnectionResponse(
    UUID id,
    UUID projectId,
    RepositoryProvider provider,
    String host,
    String ownerPath,
    String repoName,
    String defaultRef,
    String credentialRef,
    Instant createdAt,
    Instant updatedAt
) {
    public static RepositoryConnectionResponse from(RepositoryConnection c) {
        return new RepositoryConnectionResponse(c.id(), c.projectId(), c.provider(), c.host(),
            c.ownerPath(), c.repoName(), c.defaultRef(), c.credentialRef(), c.createdAt(), c.updatedAt());
    }
}
