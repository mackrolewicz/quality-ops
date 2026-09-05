package com.qualityops.api.common.ratelimit;

import com.qualityops.api.support.AbstractRedisPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-008 §6 — the Redis fixed-window counter. Named {@code *IT} (not the plan's
 * {@code RedisRateLimiterTest}) so it runs in failsafe, per the repo convention
 * that every Testcontainers test is an integration test.
 */
class RedisRateLimiterIT extends AbstractRedisPostgresIT {

    @Autowired private RedisRateLimiter limiter;

    @Test
    void check_underLimit_isAllowedAndCountsUp() {
        UUID org = UUID.randomUUID();

        var first = limiter.check(org, "op", 5, Duration.ofHours(1));
        var second = limiter.check(org, "op", 5, Duration.ofHours(1));
        var third = limiter.check(org, "op", 5, Duration.ofHours(1));

        assertThat(first.allowed()).isTrue();
        assertThat(first.count()).isEqualTo(1);
        assertThat(first.remaining()).isEqualTo(4);
        assertThat(second.count()).isEqualTo(2);
        assertThat(third.count()).isEqualTo(3);
        assertThat(third.remaining()).isEqualTo(2);
    }

    @Test
    void check_limitPlusOne_isRejected() {
        UUID org = UUID.randomUUID();

        limiter.check(org, "op", 2, Duration.ofHours(1));
        limiter.check(org, "op", 2, Duration.ofHours(1));
        var third = limiter.check(org, "op", 2, Duration.ofHours(1));

        assertThat(third.allowed()).isFalse();
        assertThat(third.remaining()).isZero();
        assertThat(third.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void check_afterWindowElapses_counterResets() throws InterruptedException {
        UUID org = UUID.randomUUID();

        assertThat(limiter.check(org, "op", 1, Duration.ofSeconds(1)).allowed()).isTrue();
        assertThat(limiter.check(org, "op", 1, Duration.ofSeconds(1)).allowed()).isFalse();

        Thread.sleep(1_100);

        var afterReset = limiter.check(org, "op", 1, Duration.ofSeconds(1));
        assertThat(afterReset.allowed()).isTrue();
        assertThat(afterReset.count()).isEqualTo(1);
    }

    @Test
    void check_differentOrg_hasIndependentCounter() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        limiter.check(orgA, "op", 1, Duration.ofHours(1));
        assertThat(limiter.check(orgA, "op", 1, Duration.ofHours(1)).allowed()).isFalse();

        var orgBFirst = limiter.check(orgB, "op", 1, Duration.ofHours(1));
        assertThat(orgBFirst.allowed()).isTrue();
        assertThat(orgBFirst.count()).isEqualTo(1);
    }

    @Test
    void check_differentOperation_independentCounter() {
        UUID org = UUID.randomUUID();

        limiter.check(org, "run.trigger", 1, Duration.ofHours(1));
        assertThat(limiter.check(org, "run.trigger", 1, Duration.ofHours(1)).allowed()).isFalse();

        var otherOp = limiter.check(org, "ci.run", 1, Duration.ofHours(1));
        assertThat(otherOp.allowed()).isTrue();
        assertThat(otherOp.count()).isEqualTo(1);
    }
}
