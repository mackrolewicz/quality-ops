package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qualityops.jwt")
public record JwtProperties(
    String secret,
    int accessTokenExpiryMinutes,
    int refreshTokenExpiryDays
) {}
