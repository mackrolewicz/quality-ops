package com.qualityops.gateway.config;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
            .map(RateLimitConfig::hostAddress)
            .defaultIfEmpty("unknown");
    }

    private static String hostAddress(InetSocketAddress remoteAddress) {
        return remoteAddress.getAddress() == null
            ? remoteAddress.getHostString()
            : remoteAddress.getAddress().getHostAddress();
    }
}
