package com.qualityops.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests that need the real Flyway-migrated schema running
 * on a throwaway PostgreSQL 16 container. Kafka listeners are disabled so the
 * context boots without a broker; {@code ddl-auto=validate} and
 * {@code spring.flyway.enabled=true} are inherited from {@code application.yml},
 * so a successful context start already proves Flyway and Hibernate agree.
 */
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    // B7 (ADR-006): stop KafkaAdmin from reaching a broker to auto-create the 4
    // unconditional @Bean NewTopics during non-Kafka context starts.
    "spring.kafka.admin.auto-create=false",
    // No shared IT base builds a MinIO client — the read path is exercised only by
    // the dedicated MinIOContainer test (ADR-005 §5, watch-out #13).
    "qualityops.artifacts.enabled=false",
    // Phase 2C (ADR-006): keep the tick/dispatch/maintenance @Scheduled jobs off
    // their own timers during unrelated ITs. Scheduling/dispatch ITs drive
    // ScheduleTickJob.tick() / QueueDispatchService.dispatchAvailable() directly.
    "qualityops.scheduling.jobs-enabled=false",
    // Phase 2E (ADR-008): the ~90 existing ITs have no Redis container — keep the
    // cache manager / WebSocket broker / rate-limit interceptor out of their
    // context. WP3/WP5/WP6 ITs re-enable via AbstractRedisPostgresIT.
    "qualityops.cache.enabled=false",
    "qualityops.ws.enabled=false",
    "qualityops.ratelimit.enabled=false"
})
@Testcontainers
public abstract class AbstractPostgresIT {

    // Singleton container shared by every IT in the JVM; Ryuk removes it at exit,
    // so there is no leak despite the never-closed warning. max_connections is
    // raised from the 100 default: the Spring TestContext cache holds many
    // distinct @SpringBootTest contexts alive at once (more since Phase 2E added
    // Redis/WebSocket/rate-limit permutations), each with its own Hikari pool.
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Keep each cached context's footprint small and let idle pools release
        // their connections, so a long IT run never exhausts the shared server.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.idle-timeout", () -> "10000");
    }
}
