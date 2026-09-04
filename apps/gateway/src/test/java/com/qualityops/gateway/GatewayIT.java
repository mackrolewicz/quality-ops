package com.qualityops.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the gateway's Phase 1 responsibilities: path-preserving
 * routing, security headers, and Redis-backed per-client rate limiting. The upstream
 * API is stubbed with {@link MockWebServer}; the {@code RequestRateLimiter} filter is
 * backed by a real Redis container (without it the first proxied request 500s).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final MockWebServer UPSTREAM = new MockWebServer();

    static {
        REDIS.start();
        UPSTREAM.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":\"ok\"}");
            }
        });
        try {
            UPSTREAM.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start MockWebServer upstream", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("API_URL", () -> "http://localhost:" + UPSTREAM.getPort());
    }

    @AfterAll
    static void stopUpstream() throws IOException {
        UPSTREAM.shutdown();
    }

    @BeforeEach
    void resetRateLimiterBuckets() throws java.io.IOException, InterruptedException {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
    }

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Test
    void proxiedRequest_preservesRequestPath_toUpstream() throws InterruptedException {
        drainUpstreamQueue();

        client().get().uri("/api/v1/ping").exchange().expectStatus().isOk();

        RecordedRequest forwarded = UPSTREAM.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/api/v1/ping");
    }

    @Test
    void proxiedResponse_carriesSecurityHeaders() {
        client().get().uri("/api/v1/ping").exchange()
            .expectStatus().isOk()
            .expectHeader().value("Strict-Transport-Security", h -> assertThat(h).isNotBlank())
            .expectHeader().value("Content-Security-Policy", h -> assertThat(h).isNotBlank())
            .expectHeader().value("Referrer-Policy", h -> assertThat(h).isNotBlank())
            .expectHeader().value("Permissions-Policy", h -> assertThat(h).isNotBlank());
    }

    @Test
    void proxiedResponse_exposesRateLimitRemainingHeader() {
        client().get().uri("/api/v1/ping").exchange()
            .expectStatus().isOk()
            .expectHeader().value("X-RateLimit-Remaining", h -> assertThat(h).isNotBlank());
    }

    @Test
    void burstAboveCapacity_isRateLimitedWith429() {
        boolean sawTooManyRequests = false;
        for (int i = 0; i < 400 && !sawTooManyRequests; i++) {
            int status = client().get().uri("/api/v1/ping").exchange()
                .returnResult(Void.class).getStatus().value();
            sawTooManyRequests = status == 429;
        }
        assertThat(sawTooManyRequests)
            .as("a sustained burst above burstCapacity should yield at least one 429")
            .isTrue();
    }

    private void drainUpstreamQueue() throws InterruptedException {
        while (UPSTREAM.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard requests recorded by earlier tests
        }
    }
}
