package com.qualityops.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.config.WebSocketProperties;
import com.qualityops.api.realtime.adapter.out.StompRunProgressNotifier;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RunProgressNotifierTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private QueueMetrics metrics;

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final WebSocketProperties props =
        new WebSocketProperties(true, "qualityops:ws:runs", List.of("http://localhost:5173"));

    private StompRunProgressNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new StompRunProgressNotifier(redis, props, mapper, metrics, simpMessagingTemplate);
    }

    @Test
    void publish_redisThrows_doesNotPropagate_andSendsLocalFallback() {
        doThrow(new RedisConnectionFailureException("redis down"))
            .when(redis).convertAndSend(any(), any());
        var event = RunProgressEvent.status(UUID.randomUUID(), UUID.randomUUID(),
            "PASSED", "COMPLETED", Instant.now());

        assertThatNoException().isThrownBy(() -> notifier.publish(event));

        verify(simpMessagingTemplate).convertAndSend("/topic/runs/" + event.runId(), event);
        verify(metrics).wsMessageSent("local");
    }

    @Test
    void publish_happyPath_sendsJsonToConfiguredRedisChannel_noLocalSend() throws Exception {
        var event = RunProgressEvent.status(UUID.randomUUID(), UUID.randomUUID(),
            "RUNNING", "RUNNING", Instant.now());

        notifier.publish(event);

        verify(redis).convertAndSend("qualityops:ws:runs", mapper.writeValueAsString(event));
        verifyNoInteractions(simpMessagingTemplate);
        verifyNoInteractions(metrics);
    }
}
