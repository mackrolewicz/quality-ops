package com.qualityops.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-org eviction of the dashboard read caches (ADR-008 §4), driven from
 * {@code RunLifecycleService} after a terminal run transition moved a row.
 * {@code SCAN} + {@code DEL} of {@code <cache>::<orgId>:*} via {@link StringRedisTemplate}.
 *
 * <p>Never throws: it runs inside the {@code api-execution} Kafka consumer
 * transaction, so a Redis blip must not roll back the authoritative
 * {@code test_runs} write. Entries also expire on their own after the 30 s TTL.
 * A no-op when {@code qualityops.cache.enabled=false}.
 */
@Component
public class DashboardCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(DashboardCacheInvalidator.class);
    private static final List<String> CACHES =
        List.of("analytics.flaky", "analytics.trends", "analytics.slow", "runs.list");

    private final StringRedisTemplate redis;
    private final QueueMetrics metrics;
    private final CacheProperties cacheProperties;

    public DashboardCacheInvalidator(StringRedisTemplate redis,
                                     QueueMetrics metrics,
                                     CacheProperties cacheProperties) {
        this.redis = redis;
        this.metrics = metrics;
        this.cacheProperties = cacheProperties;
    }

    public void evictForOrg(UUID orgId) {
        if (!cacheProperties.enabled()) {
            return;
        }
        try {
            for (String cache : CACHES) {
                Set<String> keys = redis.keys(cache + "::" + orgId + ":*");
                if (keys != null && !keys.isEmpty()) {
                    redis.delete(keys);
                }
            }
        } catch (RuntimeException e) {
            log.warn("dashboard cache evict for org {} failed", orgId, e);
            metrics.cacheError("evict");
        }
    }
}
