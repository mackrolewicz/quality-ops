package com.qualityops.api.identity.adapter.out.persistence;

import com.qualityops.api.identity.application.port.out.RefreshTokenRepository;
import com.qualityops.api.identity.domain.RefreshToken;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    RefreshTokenRepositoryAdapter(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        return jpa.save(RefreshTokenEntity.fromDomain(token)).toDomain();
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHashAndRevokedFalse(tokenHash).map(RefreshTokenEntity::toDomain);
    }

    @Override
    public void revokeByTokenHash(String tokenHash) {
        jpa.revokeByTokenHash(tokenHash);
    }

    @Override
    public void revokeAllForUser(UUID userId) {
        jpa.revokeAllByUserId(userId);
    }
}
