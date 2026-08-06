package com.qualityops.api.identity.application.service;

import com.qualityops.api.config.JwtProperties;
import com.qualityops.api.config.JwtService;
import com.qualityops.api.identity.application.port.in.LoginUseCase;
import com.qualityops.api.identity.application.port.in.LogoutUseCase;
import com.qualityops.api.identity.application.port.in.RefreshTokenUseCase;
import com.qualityops.api.identity.application.port.out.RefreshTokenRepository;
import com.qualityops.api.identity.application.port.out.UserRepository;
import com.qualityops.api.identity.domain.RefreshToken;
import com.qualityops.api.identity.dto.LoginRequest;
import com.qualityops.api.identity.dto.LoginResponse;
import com.qualityops.api.identity.dto.TokenResponse;
import com.qualityops.api.identity.exception.InvalidRefreshTokenException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Transactional
public class AuthService implements LoginUseCase, RefreshTokenUseCase, LogoutUseCase {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.hashedPassword())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String accessToken = jwtService.generateAccessToken(user.id(), user.orgId(), user.role());

        String rawRefresh = UUID.randomUUID().toString();
        var refreshToken = new RefreshToken(
            UUID.randomUUID(),
            user.id(),
            user.orgId(),
            hashToken(rawRefresh),
            Instant.now().plus(jwtProperties.refreshTokenExpiryDays(), ChronoUnit.DAYS),
            false,
            Instant.now()
        );
        refreshTokenRepository.save(refreshToken);

        return new LoginResponse(accessToken, "Bearer",
            jwtProperties.accessTokenExpiryMinutes() * 60L, rawRefresh);
    }

    @Override
    public TokenResponse refresh(String rawRefreshToken) {
        String tokenHash = hashToken(rawRefreshToken);
        var existing = refreshTokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (!existing.isValid()) {
            throw new InvalidRefreshTokenException("Refresh token expired or revoked");
        }

        // Rotate: revoke old, issue new pair
        refreshTokenRepository.revokeByTokenHash(tokenHash);

        var user = userRepository.findById(existing.userId())
            .orElseThrow(() -> new InvalidRefreshTokenException("User not found"));

        String newAccess = jwtService.generateAccessToken(user.id(), user.orgId(), user.role());
        String newRaw = UUID.randomUUID().toString();
        var newToken = new RefreshToken(
            UUID.randomUUID(),
            user.id(),
            user.orgId(),
            hashToken(newRaw),
            Instant.now().plus(jwtProperties.refreshTokenExpiryDays(), ChronoUnit.DAYS),
            false,
            Instant.now()
        );
        refreshTokenRepository.save(newToken);

        return new TokenResponse(newAccess, "Bearer",
            jwtProperties.accessTokenExpiryMinutes() * 60L, newRaw);
    }

    @Override
    public void logout(String rawRefreshToken) {
        // Idempotent: no error if the token is already revoked or not found
        refreshTokenRepository.revokeByTokenHash(hashToken(rawRefreshToken));
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
