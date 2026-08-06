package com.qualityops.api.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
    UUID id,
    UUID userId,
    UUID orgId,
    String tokenHash,
    Instant expiresAt,
    boolean revoked,
    Instant createdAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
