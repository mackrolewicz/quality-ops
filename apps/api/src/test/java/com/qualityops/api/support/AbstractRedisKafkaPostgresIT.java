package com.qualityops.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests that need Redis <em>and</em> the in-process Kafka
 * consumers end to end — the WebSocket run-progress fan-out (WP5). Adds the same
 * singleton Redis 7 container as {@link AbstractRedisPostgresIT} on top of the
 * embedded-Kafka + PostgreSQL base.
 */
public abstract class AbstractRedisKafkaPostgresIT extends AbstractKafkaPostgresIT {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);
    }
}
