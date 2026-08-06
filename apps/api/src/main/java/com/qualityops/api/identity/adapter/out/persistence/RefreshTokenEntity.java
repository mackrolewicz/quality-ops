package com.qualityops.api.identity.adapter.out.persistence;

import com.qualityops.api.identity.domain.RefreshToken;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshTokenEntity() {}

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    RefreshToken toDomain() {
        return new RefreshToken(id, userId, orgId, tokenHash, expiresAt, revoked, createdAt);
    }

    static RefreshTokenEntity fromDomain(RefreshToken token) {
        var entity = new RefreshTokenEntity();
        entity.id = token.id();
        entity.userId = token.userId();
        entity.orgId = token.orgId();
        entity.tokenHash = token.tokenHash();
        entity.expiresAt = token.expiresAt();
        entity.revoked = token.revoked();
        return entity;
    }
}
