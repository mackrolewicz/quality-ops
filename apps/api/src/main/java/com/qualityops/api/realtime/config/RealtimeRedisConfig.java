package com.qualityops.api.realtime.config;

import com.qualityops.api.config.WebSocketProperties;
import com.qualityops.api.realtime.adapter.out.RedisRunEventBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes the {@link RedisRunEventBridge} to the shared run-progress channel
 * (ADR-008 §5). One container per replica; the bridge fans each message out to
 * that replica's local STOMP sessions.
 */
@Configuration
@ConditionalOnProperty(name = "qualityops.ws.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeRedisConfig {

    @Bean
    RedisMessageListenerContainer wsRedisListenerContainer(RedisConnectionFactory connectionFactory,
                                                           RedisRunEventBridge bridge,
                                                           WebSocketProperties props) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bridge, new ChannelTopic(props.redisChannel()));
        return container;
    }
}
