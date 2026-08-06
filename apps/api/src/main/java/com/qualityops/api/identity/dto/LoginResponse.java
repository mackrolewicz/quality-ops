package com.qualityops.api.identity.dto;

public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken
) {}
