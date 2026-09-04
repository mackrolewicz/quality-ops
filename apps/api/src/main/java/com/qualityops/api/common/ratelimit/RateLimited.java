package com.qualityops.api.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler method as subject to per-tenant, per-operation
 * application rate limiting (ADR-008 &sect;6). Enforced by
 * {@link RateLimitInterceptor} (a Spring MVC {@code HandlerInterceptor}, NOT an
 * AOP aspect) so it runs before the controller body, can set response headers,
 * and is immune to the AOP self-invocation limitation.
 *
 * <p>{@code limit} and {@code window} are {@code String} so a
 * {@code "${qualityops.ratelimit.*}"} placeholder resolves against the
 * {@code Environment} at request time. {@code window} must be an ISO-8601
 * {@link java.time.Duration} literal (e.g. {@code PT1H}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    String operation();

    String limit();

    String window();
}
