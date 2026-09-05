package com.qualityops.worker.config;

import com.qualityops.worker.config.WorkerExecutionProperties.Artifacts;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.support.TestProps;
import io.minio.BucketExistsArgs;
import io.minio.GetBucketLifecycleArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BucketBootstrapIT {

    private static final String BUCKET = "qualityops-artifacts";
    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z");

    private static MinioClient client;

    @BeforeAll
    static void start() {
        MINIO.start();
        client = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
    }

    @AfterAll
    static void stop() {
        MINIO.stop();
    }

    @Test
    void run_createsBucketAndLifecycleRule_andIsIdempotentOnReRun() throws Exception {
        var bootstrap = new BucketBootstrap(client, props());

        bootstrap.run(null);
        bootstrap.run(null);   // idempotent

        assertThat(client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())).isTrue();
        var lifecycle = client.getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(BUCKET).build());
        assertThat(lifecycle).isNotNull();
        assertThat(lifecycle.rules()).anySatisfy(r ->
            assertThat(r.expiration().days()).isEqualTo(30));
    }

    private WorkerExecutionProperties props() {
        var artifacts = new Artifacts(true, MINIO.getS3URL(), BUCKET,
            MINIO.getUserName(), MINIO.getPassword(), "us-east-1", Artifacts.Sse.NONE, true,
            Duration.ofSeconds(10), 10_485_760L, 30, "/tmp/staging", Duration.ofHours(2), true, false);
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), artifacts,
            TestProps.retry(), TestProps.secrets());
    }
}
