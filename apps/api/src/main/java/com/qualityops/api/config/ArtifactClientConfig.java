package com.qualityops.api.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API's read-only MinIO client. Only wired when
 * {@code qualityops.artifacts.enabled=true} — unit builds ({@code -DskipITs})
 * set it false and never construct a MinIO client.
 */
@Configuration
@ConditionalOnProperty(prefix = "qualityops.artifacts", name = "enabled",
    havingValue = "true", matchIfMissing = false)
class ArtifactClientConfig {

    @Bean(destroyMethod = "close")
    MinioClient artifactReadClient(ArtifactClientProperties props) {
        return MinioClient.builder()
            .endpoint(props.endpoint())
            .credentials(props.accessKey(), props.secretKey())
            .region(props.region())
            .build();
    }
}
