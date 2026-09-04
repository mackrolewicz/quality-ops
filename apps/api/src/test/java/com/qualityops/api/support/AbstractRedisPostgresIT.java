package com.qualityops.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests that need Redis in addition to the Flyway-migrated
 * PostgreSQL schema — the dashboard cache (WP3) and application rate limiting
 * (WP6). Adds a singleton Redis 7 container and flips
 * {@code qualityops.cache.enabled} / {@code qualityops.ratelimit.enabled} back on
 * (they are {@code false} on {@link AbstractPostgresIT}).
 */
public abstract class AbstractRedisPostgresIT extends AbstractPostgresIT {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);
    }
}
