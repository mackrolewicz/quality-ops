package com.qualityops.api.common.ratelimit;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.RateLimitProperties;
import com.qualityops.api.config.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * Enforces {@link RateLimited} on the controller methods that front the
 * run-enqueue and CI paths (ADR-008 &sect;6). Runs before the controller body,
 * sets {@code X-RateLimit-*} on every response, {@code Retry-After} + throws
 * {@link RateLimitExceededException} on rejection. Fails open on a Redis outage.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RedisRateLimiter limiter;
    private final RateLimitProperties props;
    private final QueueMetrics metrics;
    private final Environment env;

    public RateLimitInterceptor(RedisRateLimiter limiter, RateLimitProperties props,
                                QueueMetrics metrics, Environment env) {
        this.limiter = limiter;
        this.props = props;
        this.metrics = metrics;
        this.env = env;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!props.enabled() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited annotation = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            return true;
        }
        UserPrincipal principal = currentPrincipal();
        if (principal == null) {
            return true;
        }

        long limit = Long.parseLong(env.resolvePlaceholders(annotation.limit()).trim());
        Duration window = Duration.parse(env.resolvePlaceholders(annotation.window()).trim());

        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.check(principal.orgId(), annotation.operation(), limit, window);
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            if (props.failOpen()) {
                metrics.rateLimitError();
                log.warn("rate limiter Redis error - failing open for op {}", annotation.operation(), e);
                return true;
            }
            throw e;
        }

        response.setHeader("X-RateLimit-Limit", Long.toString(limit));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(decision.resetEpochSeconds()));

        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            metrics.rateLimitRejected(annotation.operation());
            throw new RateLimitExceededException(annotation.operation(), (int) limit,
                decision.retryAfterSeconds(), decision.resetEpochSeconds());
        }
        return true;
    }

    private static UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        return null;
    }
}
