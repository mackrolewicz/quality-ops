package com.qualityops.worker.execution.adapter.out.storage;

import com.qualityops.worker.config.WorkerExecutionProperties;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The write-capable MinIO client for the Worker. Only created when
 * {@code qualityops.worker.execution.artifacts.enabled=true} — unit / non-MinIO
 * integration builds (which set it false) never construct a MinIO client
 * (ADR-005 §5, watch-out #13).
 */
@Configuration
@ConditionalOnProperty(prefix = "qualityops.worker.execution.artifacts", name = "enabled",
    havingValue = "true", matchIfMissing = false)
class MinioArtifactClientConfig {

    @Bean(destroyMethod = "close")
    MinioClient artifactMinioClient(WorkerExecutionProperties props) {
        var a = props.artifacts();
        return MinioClient.builder()
            .endpoint(a.endpoint())
            .credentials(a.accessKey(), a.secretKey())
            .region(a.region())
            .build();
    }
}
