package com.qualityops.api.common.ratelimit;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.RateLimitProperties;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.identity.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock private RedisRateLimiter limiter;
    @Mock private QueueMetrics metrics;

    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @SuppressWarnings("unused")
    static final class SampleController {
        @RateLimited(operation = "run.trigger", limit = "60", window = "PT1H")
        public void limited() {
        }

        public void plain() {
        }
    }

    private HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(), SampleController.class.getMethod(method));
    }

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(limiter, new RateLimitProperties(true, true),
            metrics, new MockEnvironment());
        request = new MockHttpServletRequest("POST", "/api/v1/runs");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        var principal = new UserPrincipal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void preHandle_noAnnotation_returnsTrueNoRedisCall() throws Exception {
        authenticate();

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
        verifyNoInteractions(limiter);
    }

    @Test
    void preHandle_noPrincipal_returnsTrue() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("limited"))).isTrue();
        verifyNoInteractions(limiter);
    }

    @Test
    void preHandle_decisionAllowed_setsRateLimitHeaders_returnsTrue() throws Exception {
        authenticate();
        when(limiter.check(any(UUID.class), eq("run.trigger"), anyLong(), any()))
            .thenReturn(new RedisRateLimiter.Decision(true, 1, 60, 59, 3600, 1_000_000L));

        assertThat(interceptor.preHandle(request, response, handler("limited"))).isTrue();
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("59");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("1000000");
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    void preHandle_decisionRejected_setsRetryAfter_throwsRateLimitExceeded() throws Exception {
        authenticate();
        when(limiter.check(any(UUID.class), eq("run.trigger"), anyLong(), any()))
            .thenReturn(new RedisRateLimiter.Decision(false, 61, 60, 0, 42, 1_000_000L));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("limited")))
            .isInstanceOf(RateLimitExceededException.class);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        verify(metrics).rateLimitRejected("run.trigger");
    }

    @Test
    void preHandle_redisThrows_failOpenTrue_returnsTrue_incrementsErrors() throws Exception {
        authenticate();
        when(limiter.check(any(UUID.class), eq("run.trigger"), anyLong(), any()))
            .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(interceptor.preHandle(request, response, handler("limited"))).isTrue();
        verify(metrics).rateLimitError();
        verify(metrics, never()).rateLimitRejected(any());
    }

    @Test
    void preHandle_redisThrows_failOpenFalse_rethrows() throws Exception {
        interceptor = new RateLimitInterceptor(limiter, new RateLimitProperties(true, false),
            metrics, new MockEnvironment());
        authenticate();
        when(limiter.check(any(UUID.class), eq("run.trigger"), anyLong(), any()))
            .thenThrow(new QueryTimeoutException("slow"));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("limited")))
            .isInstanceOf(QueryTimeoutException.class);
    }
}
