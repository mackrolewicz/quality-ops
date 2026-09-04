package com.qualityops.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Fail-open cache error handling (ADR-008 §4). Every Redis failure on a
 * {@code @Cacheable} / {@code @CacheEvict} path is logged at WARN, bumps
 * {@code qualityops.cache.errors}, and <b>returns normally</b> so the annotated
 * method body runs against Postgres. A Redis outage degrades latency, never
 * correctness.
 */
class LoggingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

    private final QueueMetrics metrics;

    LoggingCacheErrorHandler(QueueMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("cache get failed cache={} key={}", cache.getName(), key, exception);
        metrics.cacheError("get");
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("cache put failed cache={} key={}", cache.getName(), key, exception);
        metrics.cacheError("put");
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("cache evict failed cache={} key={}", cache.getName(), key, exception);
        metrics.cacheError("evict");
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("cache clear failed cache={}", cache.getName(), exception);
        metrics.cacheError("clear");
    }
}
