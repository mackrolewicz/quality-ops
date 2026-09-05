package com.qualityops.worker.support;

import com.qualityops.worker.WorkerApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Worker analogue of the API's AbstractPostgresIT: one throwaway Postgres 16 for
 *  the JVM, Flyway migrates the {@code worker} schema on context start. Kafka
 *  listeners off unless a subclass re-enables them. */
@SpringBootTest(classes = WorkerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.kafka.listener.auto-startup=false",
        // B7 (ADR-006): keep KafkaAdmin from reaching a broker for topic
        // auto-create during non-Kafka context starts.
        "spring.kafka.admin.auto-create=false",
        // No IT constructs a MinIO client — the object store is exercised only by the
        // dedicated MinIOContainer ITs (ADR-005 §5, watch-out #13).
        "qualityops.worker.execution.artifacts.enabled=false",
        "qualityops.worker.execution.artifacts.bootstrap-enabled=false",
        // ADR-009 §1 — no Spring-context IT drives a real Docker daemon; the
        // repo-exec beans (DockerClient, pre-puller, sweeper) are excluded.
        // The @Tag("docker") runner ITs build DockerContainerRunner directly.
        "qualityops.repo-exec.enabled=false"
    })
@Testcontainers
public abstract class AbstractWorkerPostgresIT {

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
