package com.qualityops.api.result.adapter.out.storage;

import com.qualityops.api.config.ArtifactClientProperties;
import com.qualityops.api.result.application.port.out.ArtifactUrlSigner;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "qualityops.artifacts", name = "enabled",
    havingValue = "true", matchIfMissing = false)
class MinioArtifactUrlSigner implements ArtifactUrlSigner {

    private static final Duration MIN_TTL = Duration.ofSeconds(1);
    private static final Duration MAX_TTL = Duration.ofSeconds(900);

    private final MinioClient client;
    private final String bucket;

    MinioArtifactUrlSigner(MinioClient artifactReadClient, ArtifactClientProperties props) {
        this.client = artifactReadClient;
        this.bucket = props.bucket();
    }

    @Override
    public PresignedUrl sign(String storageKey, Duration ttl) {
        Duration clamped = clamp(ttl);
        try {
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(storageKey)
                .expiry((int) clamped.toSeconds(), TimeUnit.SECONDS)
                .build());
            return new PresignedUrl(url, Instant.now().plus(clamped));
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot presign artifact " + storageKey, e);
        }
    }

    private static Duration clamp(Duration ttl) {
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }
}
