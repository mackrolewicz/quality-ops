package com.qualityops.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Redis 7 container shared by {@link AbstractRedisPostgresIT} and
 * {@link AbstractRedisKafkaPostgresIT}. Uses the core Testcontainers
 * {@code GenericContainer} so no extra pom dependency is needed. Ryuk removes it
 * at JVM exit.
 */
final class RedisTestContainer {

    private static final int REDIS_PORT = 6379;

    @SuppressWarnings("resource") // singleton for the JVM lifetime; Ryuk reaps it.
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    private RedisTestContainer() {
    }

    /** Wires {@code spring.data.redis.*} at the mapped port and re-enables the
     *  cache + rate-limit paths the shared {@link AbstractPostgresIT} disables. */
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("qualityops.cache.enabled", () -> true);
        registry.add("qualityops.ratelimit.enabled", () -> true);
    }
}
