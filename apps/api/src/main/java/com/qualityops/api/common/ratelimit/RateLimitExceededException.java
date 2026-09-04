package com.qualityops.api.common.ratelimit;

/**
 * Thrown by {@link RateLimitInterceptor} when a {@link RateLimited} operation is
 * over its per-tenant window budget. Mapped to {@code 429 RATE_LIMITED} by
 * {@code GlobalExceptionHandler} (ADR-008 &sect;6).
 */
public class RateLimitExceededException extends RuntimeException {

    private final String operation;
    private final int limit;
    private final long retryAfterSeconds;
    private final long resetEpochSeconds;

    public RateLimitExceededException(String operation, int limit,
                                     long retryAfterSeconds, long resetEpochSeconds) {
        super("rate limit exceeded for " + operation);
        this.operation = operation;
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
        this.resetEpochSeconds = resetEpochSeconds;
    }

    public String getOperation() {
        return operation;
    }

    public int getLimit() {
        return limit;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public long getResetEpochSeconds() {
        return resetEpochSeconds;
    }
}
