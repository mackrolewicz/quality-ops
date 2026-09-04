package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** ADR-008 §5. Bound from {@code qualityops.ws.*}. Auto-registered by
 *  @ConfigurationPropertiesScan on QualityOpsApplication. */
@ConfigurationProperties("qualityops.ws")
public record WebSocketProperties(
        boolean enabled,
        String redisChannel,
        List<String> allowedOrigins
) {}
