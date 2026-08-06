package com.qualityops.api.identity.dto;

public record TokenResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken
) {}
