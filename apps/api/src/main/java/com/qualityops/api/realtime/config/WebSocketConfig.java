package com.qualityops.api.realtime.config;

import com.qualityops.api.config.WebSocketProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP-over-SockJS endpoint at {@code /ws} with the in-memory simple broker on
 * {@code /topic} (ADR-008 §5). Gated on {@code qualityops.ws.enabled} so the
 * ~200 Redis-free integration tests never boot the broker.
 *
 * <p>Transport limits are hard backpressure guards: a client that cannot keep up
 * is disconnected rather than buffered unbounded.
 */
@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name = "qualityops.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;
    private static final int MESSAGE_SIZE_LIMIT = 64 * 1024;

    private final WebSocketProperties props;
    private final StompAuthChannelInterceptor authInterceptor;

    public WebSocketConfig(WebSocketProperties props, StompAuthChannelInterceptor authInterceptor) {
        this.props = props;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(props.allowedOrigins().toArray(String[]::new))
            .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MS)
            .setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT)
            .setMessageSizeLimit(MESSAGE_SIZE_LIMIT);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
