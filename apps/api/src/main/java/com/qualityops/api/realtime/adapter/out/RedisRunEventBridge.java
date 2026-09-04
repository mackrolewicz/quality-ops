package com.qualityops.api.realtime.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives every {@link RunProgressEvent} published to the shared Redis channel
 * — local and remote — and re-broadcasts it to this replica's STOMP sessions
 * (ADR-008 §5). Also tracks the live session count for the
 * {@code qualityops.ws.sessions} gauge.
 */
@Component
@ConditionalOnProperty(name = "qualityops.ws.enabled", havingValue = "true", matchIfMissing = true)
public class RedisRunEventBridge implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisRunEventBridge.class);

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final ObjectMapper mapper;
    private final QueueMetrics metrics;
    private final AtomicInteger sessions = new AtomicInteger();

    public RedisRunEventBridge(SimpMessagingTemplate simpMessagingTemplate,
                               ObjectMapper mapper,
                               QueueMetrics metrics,
                               MeterRegistry registry) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.mapper = mapper;
        this.metrics = metrics;
        Gauge.builder("qualityops.ws.sessions", sessions, AtomicInteger::get).register(registry);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RunProgressEvent event = mapper.readValue(message.getBody(), RunProgressEvent.class);
            simpMessagingTemplate.convertAndSend("/topic/runs/" + event.runId(), event);
            metrics.wsMessageSent("redis");
        } catch (IOException e) {
            log.warn("Discarding malformed WS bridge payload on the run-progress channel", e);
        }
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        sessions.incrementAndGet();
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        sessions.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }
}
