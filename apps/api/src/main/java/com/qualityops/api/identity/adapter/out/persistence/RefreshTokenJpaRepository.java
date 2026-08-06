package com.qualityops.api.identity.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHashAndRevokedFalse(String tokenHash);

    @Transactional
    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.revoked = TRUE WHERE r.tokenHash = :tokenHash")
    void revokeByTokenHash(@Param("tokenHash") String tokenHash);

    @Transactional
    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.revoked = TRUE WHERE r.userId = :userId")
    void revokeAllByUserId(@Param("userId") UUID userId);
}
