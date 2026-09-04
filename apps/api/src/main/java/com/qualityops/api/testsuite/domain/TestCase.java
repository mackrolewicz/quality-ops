package com.qualityops.api.testsuite.domain;

import java.time.Instant;
import java.util.UUID;

public record TestCase(
    UUID id,
    UUID orgId,
    UUID suiteId,
    String name,
    String description,
    int orderIndex,
    ApiRequestSpec apiRequest,
    BrowserTestSpec browserTest,
    RepoTestSpec repoTest,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
    /** Convenience — no repository spec. Keeps pre-2F call sites compiling. */
    public TestCase(UUID id, UUID orgId, UUID suiteId, String name, String description, int orderIndex,
                    ApiRequestSpec apiRequest, BrowserTestSpec browserTest,
                    Instant createdAt, Instant updatedAt, Instant deletedAt) {
        this(id, orgId, suiteId, name, description, orderIndex, apiRequest, browserTest, null,
            createdAt, updatedAt, deletedAt);
    }
}
