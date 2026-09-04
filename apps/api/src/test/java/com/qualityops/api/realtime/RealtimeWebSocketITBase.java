package com.qualityops.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.JwtProperties;
import com.qualityops.api.config.JwtService;
import com.qualityops.api.config.WebSocketProperties;
import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import com.qualityops.api.support.AbstractRedisKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Shared wiring for the WP5 WebSocket integration tests (ADR-008 §5): a
 * {@code RANDOM_PORT} context with {@code qualityops.ws.enabled=true} on top of
 * the Redis + embedded-Kafka + PostgreSQL base, plus a {@link WebSocketStompClient}
 * over SockJS and a handful of STOMP helpers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "qualityops.ws.enabled=true",
    "qualityops.scheduling.jobs-enabled=false",
    "qualityops.artifacts.enabled=false",
    "qualityops.ratelimit.enabled=false",
    "spring.kafka.listener.auto-startup=false",
    "spring.kafka.admin.auto-create=false"
})
abstract class RealtimeWebSocketITBase extends AbstractRedisKafkaPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JwtService jwtService;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    WebSocketProperties wsProps;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ApplyRunLifecycleUseCase lifecycle;

    WebSocketStompClient stompClient;

    @BeforeEach
    void initStompClient() {
        var sockJs = new SockJsClient(List.<Transport>of(
            new WebSocketTransport(new StandardWebSocketClient())));
        stompClient = new WebSocketStompClient(sockJs);
        var converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient.setMessageConverter(converter);
    }

    String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    String tokenFor(UUID userId, UUID orgId) {
        return jwtService.generateAccessToken(userId, orgId, Role.ADMIN);
    }

    String expiredToken(UUID userId, UUID orgId) {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secret()));
        var now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("org_id", orgId.toString())
            .claim("roles", List.of("ADMIN"))
            .issuedAt(Date.from(now.minusSeconds(600)))
            .expiration(Date.from(now.minusSeconds(300)))
            .signWith(key)
            .compact();
    }

    TestSessionHandler connect(String bearerToken) {
        var handler = new TestSessionHandler();
        var connectHeaders = new StompHeaders();
        if (bearerToken != null) {
            connectHeaders.add("Authorization", "Bearer " + bearerToken);
        }
        stompClient.connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders, handler);
        return handler;
    }

    static CompletableFuture<RunProgressEvent> subscribeRunTopic(StompSession session, UUID runId) {
        var received = new CompletableFuture<RunProgressEvent>();
        session.subscribe("/topic/runs/" + runId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return RunProgressEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete((RunProgressEvent) payload);
            }
        });
        return received;
    }

    UUID seedRunningRun(UUID orgId, UUID projectId, UUID suiteId, UUID envId, UUID userId) {
        return ItFixtures.insertRun(jdbc, orgId, projectId, suiteId, envId, userId, "RUNNING", Instant.now());
    }

    UUID executionIdOf(UUID runId) {
        return jdbc.queryForObject("SELECT execution_id FROM test_runs WHERE id = ?", UUID.class, runId);
    }

    /** Captures the terminal outcomes of a connect attempt so a test can assert
     *  on either a successful CONNECTED or a rejection (ERROR frame / transport). */
    static final class TestSessionHandler extends StompSessionHandlerAdapter {

        final CompletableFuture<StompSession> connected = new CompletableFuture<>();
        final CompletableFuture<Throwable> error = new CompletableFuture<>();

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(session);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            error.complete(exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            error.complete(exception);
        }
    }
}
