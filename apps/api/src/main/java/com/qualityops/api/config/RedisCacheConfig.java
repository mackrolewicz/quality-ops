package com.qualityops.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.util.Set;

/**
 * Redis-backed dashboard read cache (ADR-008 §4): 30 s default TTL, a per-cache
 * key prefix that embeds {@code orgId} by construction ({@code <name>::<orgId>:...}),
 * JSON values, no null caching. Fail-open via {@link LoggingCacheErrorHandler}.
 *
 * <p>When {@code qualityops.cache.enabled=false} a {@link NoOpCacheManager} is
 * returned so {@code @Cacheable} becomes a transparent pass-through (the ~90
 * non-Redis integration tests).
 */
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    static final Set<String> CACHE_NAMES =
        Set.of("analytics.flaky", "analytics.trends", "analytics.slow", "runs.list");

    private final CacheProperties props;
    private final QueueMetrics metrics;
    private final ObjectMapper objectMapper;

    public RedisCacheConfig(CacheProperties props, QueueMetrics metrics, ObjectMapper objectMapper) {
        this.props = props;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        if (!props.enabled()) {
            return new NoOpCacheManager();
        }
        // A copy of the Spring-managed ObjectMapper (JavaTimeModule + ISO dates) with the
        // same "@class" default typing GenericJackson2JsonRedisSerializer applies itself —
        // needed because cached DTOs carry Instant / LocalDate values.
        var valueSerializer = GenericJackson2JsonRedisSerializer.builder()
            .objectMapper(objectMapper.copy())
            .defaultTyping(true)
            .build();
        var base = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(props.dashboardTtl())
            .computePrefixWith(name -> name + "::")
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
            .disableCachingNullValues();
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(base)
            .initialCacheNames(CACHE_NAMES)
            .enableStatistics()
            .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(metrics);
    }
}
