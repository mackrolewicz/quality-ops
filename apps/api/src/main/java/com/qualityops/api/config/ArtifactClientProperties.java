package com.qualityops.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Read-only object-store access for the API: presign GET + head only, a
 *  SEPARATE credential from the Worker's write key (ADR-005 §1.5, §G). */
@ConfigurationProperties("qualityops.artifacts")
public record ArtifactClientProperties(
        boolean enabled,
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region,
        boolean pathStyleAccess,
        Duration presignTtl) {

    /** Presign TTL clamped to [1s, 900s] (ADR-005 §1.5). */
    public Duration effectivePresignTtl() {
        Duration ttl = presignTtl == null ? Duration.ofSeconds(300) : presignTtl;
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            return Duration.ofSeconds(1);
        }
        return ttl.compareTo(Duration.ofSeconds(900)) > 0 ? Duration.ofSeconds(900) : ttl;
    }
}
