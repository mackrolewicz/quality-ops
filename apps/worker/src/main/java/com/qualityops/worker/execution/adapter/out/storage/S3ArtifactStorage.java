package com.qualityops.worker.execution.adapter.out.storage;

import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.application.port.out.ArtifactStoragePort;
import com.qualityops.worker.execution.domain.ArtifactUpload;
import com.qualityops.worker.execution.domain.StoredArtifact;
import com.qualityops.worker.execution.exception.ArtifactStoreException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.ServerSideEncryptionS3;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Map;

/**
 * S3-compatible {@link ArtifactStoragePort} backed by the MinIO Java client.
 * MinIO now, Azure Blob later (Phase 5) — same port, same key layout. Only
 * wired when {@code artifacts.enabled=true} (ADR-005 §1, §5).
 */
@Component
@ConditionalOnProperty(prefix = "qualityops.worker.execution.artifacts", name = "enabled",
    havingValue = "true", matchIfMissing = false)
public class S3ArtifactStorage implements ArtifactStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3ArtifactStorage.class);
    private static final String SHA256_META = "sha256";

    private static final ServerSideEncryptionS3 SSE_S3 = new ServerSideEncryptionS3();

    private final MinioClient client;
    private final String bucket;
    private final boolean sseS3;

    public S3ArtifactStorage(MinioClient artifactMinioClient, WorkerExecutionProperties props) {
        this.client = artifactMinioClient;
        var a = props.artifacts();
        this.bucket = a.bucket();
        this.sseS3 = a.sse() == WorkerExecutionProperties.Artifacts.Sse.S3;
    }

    @Override
    public StoredArtifact put(ArtifactUpload upload) throws ArtifactStoreException {
        String key = upload.ref().storageKey();
        try {
            var existing = statOrNull(key);
            if (existing != null && upload.sha256().equals(existing.userMetadata().get(SHA256_META))) {
                log.debug("Artifact already present at {} with matching sha256 — skipping upload", key);
                return new StoredArtifact(key, upload.contentType(), upload.sizeBytes(), upload.sha256(), true);
            }
            try (InputStream in = Files.newInputStream(upload.source())) {
                var builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, upload.sizeBytes(), -1)
                    .contentType(upload.contentType())
                    .userMetadata(Map.of(SHA256_META, upload.sha256()));
                if (sseS3) {
                    builder.sse(SSE_S3);
                }
                client.putObject(builder.build());
            }
            return new StoredArtifact(key, upload.contentType(), upload.sizeBytes(), upload.sha256(), false);
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw new ArtifactStoreException("artifact PUT failed for " + key, e);
        }
    }

    private StatObjectResponse statOrNull(String key) throws ArtifactStoreException {
        try {
            return client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return null;
            }
            throw new ArtifactStoreException("artifact stat failed for " + key, e);
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw new ArtifactStoreException("artifact stat failed for " + key, e);
        }
    }
}
