package com.qualityops.api.identity.application.port.out;

import com.qualityops.api.identity.domain.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    RefreshToken save(RefreshToken token);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void revokeByTokenHash(String tokenHash);
    void revokeAllForUser(UUID userId);
}
