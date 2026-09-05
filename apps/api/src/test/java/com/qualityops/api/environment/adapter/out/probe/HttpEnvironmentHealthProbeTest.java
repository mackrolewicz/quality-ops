package com.qualityops.api.environment.adapter.out.probe;

import com.qualityops.api.config.EnvironmentHealthProperties;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthProbe.ProbeResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 §3 — JDK HttpClient probe behaviour (MockWebServer, no Spring). */
class HttpEnvironmentHealthProbeTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static HttpEnvironmentHealthProbe probeWithTimeout(Duration timeout) {
        return new HttpEnvironmentHealthProbe(new EnvironmentHealthProperties(
            true, Duration.ofSeconds(60), Duration.ofMinutes(5), timeout,
            3, 1, 50, Duration.ofDays(14), false));
    }

    @Test
    void probe_200Response_returnsReachableWith200() {
        server.enqueue(new MockResponse().setResponseCode(200));

        ProbeResult result = probeWithTimeout(Duration.ofSeconds(5)).probe(server.url("/").toString());

        assertThat(result.reachable()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
    }

    @Test
    void probe_connectionRefused_returnsUnreachable() {
        ProbeResult result = probeWithTimeout(Duration.ofSeconds(2)).probe("http://127.0.0.1:1/");

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void probe_slowResponse_timesOutAsUnreachable() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        ProbeResult result = probeWithTimeout(Duration.ofSeconds(1)).probe(server.url("/slow").toString());

        assertThat(result.reachable()).isFalse();
    }

    @Test
    void probe_redirect_isNotFollowed() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"));

        ProbeResult result = probeWithTimeout(Duration.ofSeconds(5)).probe(server.url("/start").toString());

        assertThat(result.httpStatus()).isEqualTo(302);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
