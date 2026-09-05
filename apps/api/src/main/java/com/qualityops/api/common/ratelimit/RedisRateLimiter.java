package com.qualityops.api.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Redis fixed-window counter (ADR-008 &sect;6). One {@code INCR} per
 * {@code (orgId, operation, windowIndex)}; the first hit in a window sets a
 * {@code PEXPIRE} so the key self-reaps. A fixed window can allow up to 2x the
 * limit at a window boundary &mdash; documented and accepted for a lab; a
 * sliding-window refinement is a follow-up.
 */
@Component
public class RedisRateLimiter {

    private static final String LUA =
        "local c = redis.call('INCR', KEYS[1]); "
            + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end; "
            + "return c";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(LUA, Long.class);
    }

    /**
     * @return a {@link Decision} for the current window. {@code allowed} is
     *     {@code count <= limit}.
     */
    public Decision check(UUID orgId, String operation, long limit, Duration window) {
        long windowSeconds = Math.max(1, window.toSeconds());
        long index = Instant.now().getEpochSecond() / windowSeconds;
        String key = "ratelimit:" + orgId + ":" + operation + ":" + index;

        Long raw = redis.execute(script, List.of(key), String.valueOf(window.toMillis()));
        long count = raw == null ? 0L : raw;

        long resetEpochSeconds = (index + 1) * windowSeconds;
        long retryAfterSeconds = Math.max(1, resetEpochSeconds - Instant.now().getEpochSecond());
        return new Decision(count <= limit, count, limit,
            Math.max(0, limit - count), retryAfterSeconds, resetEpochSeconds);
    }

    public record Decision(boolean allowed, long count, long limit, long remaining,
                           long retryAfterSeconds, long resetEpochSeconds) {
    }
}
