package com.qualityops.api.scm.domain;

import com.qualityops.events.RepositoryProvider;

import java.time.Instant;
import java.util.UUID;

/** ADR-009 §4 — an org- + project-scoped GitHub/GitLab repository connection.
 *  {@code credentialRef} is the opaque resolver key only ({@code [A-Z0-9_]{1,64}}
 *  or null for a public repo); a provider token is never stored on this record. */
public record RepositoryConnection(
    UUID id,
    UUID orgId,
    UUID projectId,
    RepositoryProvider provider,
    String host,
    String ownerPath,
    String repoName,
    String defaultRef,
    String credentialRef,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
    /** Canonical "owner/name" identity used for SCM REST calls and provenance. */
    public String repoPath() {
        return ownerPath + "/" + repoName;
    }
}
