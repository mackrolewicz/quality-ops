package com.qualityops.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void ipKeyResolverUsesRemoteIpAddress() {
        var request = mock(ServerHttpRequest.class);
        var exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(request.getRemoteAddress()).thenReturn(
            new InetSocketAddress("203.0.113.7", 49152));

        var key = config.ipKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("203.0.113.7");
    }

    @Test
    void ipKeyResolverFallsBackWhenRemoteAddressIsMissing() {
        var request = mock(ServerHttpRequest.class);
        var exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(request.getRemoteAddress()).thenReturn(null);

        var key = config.ipKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("unknown");
    }
}
