package com.qualityops.worker.execution.adapter.out.storage;

import com.qualityops.events.ArtifactType;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Artifacts;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.domain.ArtifactRef;
import com.qualityops.worker.execution.domain.ArtifactUpload;
import com.qualityops.worker.execution.exception.ArtifactStoreException;
import com.qualityops.worker.support.TestProps;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MinIOContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3ArtifactStorageIT {

    private static final String BUCKET = "qualityops-artifacts";
    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z");

    private static MinioClient client;

    @BeforeAll
    static void start() throws Exception {
        MINIO.start();
        client = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stop() {
        MINIO.stop();
    }

    private S3ArtifactStorage storage(String endpoint) {
        var c = MinioClient.builder().endpoint(endpoint)
            .credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        return new S3ArtifactStorage(c, props(true));
    }

    @Test
    void put_writesObjectAtDeterministicOrgFirstKey_withSha256Metadata(@TempDir Path dir) throws Exception {
        var s = storage(MINIO.getS3URL());
        var upload = upload(dir, "attempt-0 bytes".getBytes(), 0);

        var stored = s.put(upload);

        assertThat(stored.deduped()).isFalse();
        assertThat(stored.storageKey()).isEqualTo(upload.ref().storageKey());
        assertThat(stored.storageKey()).startsWith("org/").contains("/attempt/0/SCREENSHOT/");

        var stat = client.statObject(StatObjectArgs.builder().bucket(BUCKET).object(stored.storageKey()).build());
        assertThat(stat.userMetadata()).containsEntry("sha256", upload.sha256());
        try (var in = client.getObject(GetObjectArgs.builder().bucket(BUCKET).object(stored.storageKey()).build())) {
            assertThat(in.readAllBytes()).isEqualTo("attempt-0 bytes".getBytes());
        }
    }

    @Test
    void put_sameBytesTwice_secondCallIsDeduped(@TempDir Path dir) throws Exception {
        var s = storage(MINIO.getS3URL());
        var upload = upload(dir, "identical".getBytes(), 1);

        s.put(upload);
        var second = s.put(upload);

        assertThat(second.deduped()).isTrue();
    }

    @Test
    void put_endpointUnreachable_throwsArtifactStoreException(@TempDir Path dir) throws IOException {
        var s = storage("http://127.0.0.1:1");
        var upload = upload(dir, "x".getBytes(), 0);

        assertThatThrownBy(() -> s.put(upload)).isInstanceOf(ArtifactStoreException.class);
    }

    private ArtifactUpload upload(Path dir, byte[] bytes, int epoch) throws IOException {
        Path f = dir.resolve("shot-" + UUID.randomUUID() + ".png");
        Files.write(f, bytes);
        var ref = new ArtifactRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            epoch, ArtifactType.SCREENSHOT, f.getFileName().toString());
        return new ArtifactUpload(ref, f, "image/png", bytes.length, sha256(bytes));
    }

    private static String sha256(byte[] b) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WorkerExecutionProperties props(boolean enabled) {
        var artifacts = new Artifacts(enabled, MINIO.getS3URL(), BUCKET,
            MINIO.getUserName(), MINIO.getPassword(), "us-east-1", Artifacts.Sse.NONE, true,
            Duration.ofSeconds(10), 10_485_760L, 30, "/tmp/staging", Duration.ofHours(2), false, false);
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), artifacts,
            TestProps.retry(), TestProps.secrets());
    }
}
