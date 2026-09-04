package com.qualityops.api.config;

import com.qualityops.api.common.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link RateLimitInterceptor} on the controller paths that front the
 * run-enqueue / CI submission flows (ADR-008 &sect;6) and the SCM "test
 * connection" probe (ADR-009 &sect;11). The interceptor itself no-ops unless the
 * handler method carries {@code @RateLimited}.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/api/v1/runs", "/api/v1/ci/runs",
                "/api/v1/repository-connections/*/test");
    }
}
