package com.qualityops.api.realtime.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.WebSocketProperties;
import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis pub/sub fan-out for {@link RunProgressEvent}s (ADR-008 §5). The happy
 * path publishes JSON to the shared channel; every replica's
 * {@link RedisRunEventBridge} (including this one) then delivers to its local
 * STOMP sessions. On a serialization or Redis failure we fall back to a direct
 * local send so the originating replica's clients still get the frame. Never
 * rethrows — a Kafka consumer transaction must not roll back on a WS problem.
 */
@Component
@ConditionalOnProperty(name = "qualityops.ws.enabled", havingValue = "true", matchIfMissing = true)
public class StompRunProgressNotifier implements RunProgressNotifier {

    private static final Logger log = LoggerFactory.getLogger(StompRunProgressNotifier.class);

    private final StringRedisTemplate redis;
    private final WebSocketProperties props;
    private final ObjectMapper mapper;
    private final QueueMetrics metrics;
    private final SimpMessagingTemplate simpMessagingTemplate;

    public StompRunProgressNotifier(StringRedisTemplate redis,
                                    WebSocketProperties props,
                                    ObjectMapper mapper,
                                    QueueMetrics metrics,
                                    SimpMessagingTemplate simpMessagingTemplate) {
        this.redis = redis;
        this.props = props;
        this.mapper = mapper;
        this.metrics = metrics;
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Override
    public void publish(RunProgressEvent event) {
        try {
            redis.convertAndSend(props.redisChannel(), mapper.writeValueAsString(event));
        } catch (JsonProcessingException | DataAccessException e) {
            log.warn("WS redis publish failed for run {} — local-only fallback", event.runId(), e);
            sendLocal(event);
        }
    }

    private void sendLocal(RunProgressEvent event) {
        try {
            simpMessagingTemplate.convertAndSend("/topic/runs/" + event.runId(), event);
            metrics.wsMessageSent("local");
        } catch (RuntimeException e) {
            log.warn("WS local fallback send also failed for run {}", event.runId(), e);
        }
    }
}
