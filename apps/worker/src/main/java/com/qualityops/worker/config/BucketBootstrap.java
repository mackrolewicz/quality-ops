package com.qualityops.worker.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.errors.MinioException;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Ensures the artifact bucket exists and carries a retention lifecycle rule.
 * Gated by {@code artifacts.bootstrap-enabled} (true by default, {@code false}
 * in tests and in the dev compose stack where the {@code minio-bootstrap}
 * service owns provisioning).
 *
 * <p>Failure policy: a <em>missing bucket that cannot be created</em> is fatal —
 * a deployment that expects the Worker to bootstrap its own store must have a
 * working store. Everything else (the bucket already exists but the lifecycle
 * rule cannot be set, e.g. a least-privilege key without {@code PutBucketLifecycle})
 * is logged and tolerated (ADR-005 §1.3).
 */
@Component
@ConditionalOnProperty(prefix = "qualityops.worker.execution.artifacts", name = "bootstrap-enabled",
    havingValue = "true", matchIfMissing = false)
class BucketBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketBootstrap.class);

    private final MinioClient client;
    private final WorkerExecutionProperties.Artifacts artifacts;

    BucketBootstrap(MinioClient artifactMinioClient, WorkerExecutionProperties props) {
        this.client = artifactMinioClient;
        this.artifacts = props.artifacts();
    }

    @Override
    public void run(ApplicationArguments args) throws MinioException, IOException, GeneralSecurityException {
        ensureBucket();
        try {
            ensureLifecycle();
        } catch (MinioException | IOException | GeneralSecurityException e) {
            log.warn("Could not set the {}-day retention rule on bucket {} ({}: {}) — "
                    + "the compose minio-bootstrap service / object-store policy is expected to own it",
                artifacts.retentionDays(), artifacts.bucket(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** A bucket that is absent and cannot be created is fatal — propagate. */
    private void ensureBucket() throws MinioException, IOException, GeneralSecurityException {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(artifacts.bucket()).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(artifacts.bucket()).build());
            log.info("Created artifact bucket {}", artifacts.bucket());
        }
    }

    private void ensureLifecycle() throws MinioException, IOException, GeneralSecurityException {
        var expiration = new Expiration((io.minio.messages.ResponseDate) null, artifacts.retentionDays(), null);
        var rule = new LifecycleRule(Status.ENABLED, null, expiration, new RuleFilter(""),
            "expire-artifacts", null, null, null);
        client.setBucketLifecycle(SetBucketLifecycleArgs.builder()
            .bucket(artifacts.bucket())
            .config(new LifecycleConfiguration(List.of(rule)))
            .build());
        log.info("Applied {}-day expiry lifecycle rule to bucket {}",
            artifacts.retentionDays(), artifacts.bucket());
    }
}
