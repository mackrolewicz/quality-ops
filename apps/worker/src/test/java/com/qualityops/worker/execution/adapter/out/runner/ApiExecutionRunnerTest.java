package com.qualityops.worker.execution.adapter.out.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.ApiAssertion;
import com.qualityops.events.ApiRequestSnapshot;
import com.qualityops.events.HttpHeader;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import com.qualityops.worker.support.TestProps;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every test carries a hard {@link Timeout} so a regression in the send loop
 * fails the build fast instead of hanging it. The SSRF allowlist is derived from
 * the live {@link MockWebServer} host (it can be {@code localhost} or
 * {@code 127.0.0.1} depending on the machine); a mismatch previously blocked the
 * request and left {@code server.takeRequest()} parked forever.
 */
@Timeout(20)
class ApiExecutionRunnerTest {

    private static final int SAMPLE_BYTES = 4096;

    private MockWebServer server;
    private ApiExecutionRunner runner;
    private WorkerExecutionProperties props;
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        props = props(server.url("/").host());
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        var redactor = new Redactor(props);
        runner = new ApiExecutionRunner(client, new TargetValidator(props), redactor,
            new AssertionEvaluator(new ObjectMapper(), redactor, props), props,
            new com.qualityops.worker.support.StubSecretResolver());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void execute_status200_andBodyContainsAssertionPasses_returnsPassed() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("hello world"));

        var result = runner.execute(ctx(spec("GET", 200,
            List.of(new ApiAssertion(ApiAssertion.Type.BODY_CONTAINS, "", "world")))));

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
    }

    @Test
    void execute_fastSuccess_returnsWithinBound() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        long start = System.nanoTime();
        var result = runner.execute(ctx(spec("GET", 200, List.of())));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void execute_wrongStatus_returnsFailed_withStatusEqualsOutcome() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        var result = runner.execute(ctx(spec("GET", 200, List.of())));

        assertThat(result.status()).isEqualTo(CaseStatus.FAILED);
        assertThat(result.reason()).contains("STATUS_EQUALS").contains("200").contains("500");
    }

    @Test
    void execute_slowResponse_returnsTimeout_withinBoundedWallClock() {
        // Server accepts the connection and never replies. The 200 ms per-request
        // timeout must fire and the send loop must return promptly.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var spec = new ApiRequestSnapshot("GET", server.url("/").toString(), List.of(), null, 200,
            200, null, List.of());

        long start = System.nanoTime();
        var result = runner.execute(ctx(spec));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(CaseStatus.TIMEOUT);
        assertThat(result.reason()).contains("exceeded");
        // per-request timeout 200 ms + monotonic backstop grace 1 s + poll slack:
        // always well under 5 s no matter what the client does.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void execute_bodyUnderCap_recordsExactByteCount_notTruncated_truncatedSample() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("x".repeat(20_480)));

        var result = runner.execute(ctx(spec("GET", 200, List.of())));

        assertThat(result.response().responseBodyBytes()).isEqualTo(20_480L);
        assertThat(result.response().bodyTruncated()).isFalse();
        assertThat(result.response().bodySample().length()).isLessThanOrEqualTo(SAMPLE_BYTES);
    }

    @Test
    void execute_bodyOverCap_retainsAtMostCap_marksTruncated_andTerminates() {
        // 2 MB body vs the 1 MB per-case cap. The bounded handler retains ≤ cap,
        // cancels the transfer, and returns — the full body is never buffered.
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("y".repeat(2 * 1_048_576))
            .setBodyDelay(1, TimeUnit.SECONDS));

        long start = System.nanoTime();
        var result = runner.execute(ctx(spec("GET", 200, List.of())));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
        assertThat(result.response().bodyTruncated()).isTrue();
        assertThat(result.response().responseBodyBytes()).isGreaterThanOrEqualTo(1_048_576L);
        assertThat(result.response().bodySample().length()).isLessThanOrEqualTo(SAMPLE_BYTES);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void execute_cancelledMidFlight_returnsError_withinBound() {
        // Server accepts and never replies; without cancellation the call would
        // sit until the 10 s default timeout. The token reports cancelled once
        // the request is in flight (false for the pre-send check, then true).
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var counting = new CountingCancellationToken(1);

        long start = System.nanoTime();
        var result = runner.execute(ctxWith(spec("GET", 200, List.of()), counting));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(result.reason()).isEqualTo("run cancelled");
        // Returned far below the 10 s request timeout ⇒ the in-flight future was
        // cancelled, not awaited.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void execute_connectionRefused_returnsError() {
        var spec = new ApiRequestSnapshot("GET", "http://" + server.url("/").host() + ":1/",
            List.of(), null, 200, null, null, List.of());

        var result = runner.execute(ctx(spec));

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(result.reason()).isEqualTo("connection error");
    }

    @Test
    void execute_redactsAuthorizationHeaderInRequestMetadata() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        var spec = new ApiRequestSnapshot("GET", server.url("/").toString(),
            List.of(new HttpHeader("Authorization", "Bearer supersecret")), null, 200, null, null, List.of());

        var result = runner.execute(ctx(spec));

        assertThat(result.request().redactedHeaders().get("Authorization")).isEqualTo("***REDACTED***");
    }

    @Test
    void execute_nonGet_sendsIdempotencyKeyHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        var spec = new ApiRequestSnapshot("POST", server.url("/").toString(), List.of(), "{}", 200, null, null,
            List.of());

        runner.execute(ctxWith(spec, CancellationToken.never()));

        var recorded = server.takeRequest(3, TimeUnit.SECONDS); // bounded — never park the test
        assertThat(recorded).as("request must reach the server (SSRF allowlist covers its host)").isNotNull();
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo(executionId.toString());
    }

    @Test
    void execute_blockedTarget_returnsBlocked() {
        var spec = new ApiRequestSnapshot("GET", "http://169.254.169.254/latest/meta-data", List.of(), null,
            null, null, null, List.of());

        var result = runner.execute(ctx(spec));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
    }

    @Test
    void execute_bodyContainsFails_persistBodySnippetsFalse_reasonOmitsResponseText() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("secret payload"));

        var result = runner.execute(ctx(spec("GET", 200,
            List.of(new ApiAssertion(ApiAssertion.Type.BODY_CONTAINS, "", "absent")))));

        assertThat(result.status()).isEqualTo(CaseStatus.FAILED);
        assertThat(result.reason()).startsWith("BODY_CONTAINS expected");
        assertThat(result.reason()).doesNotContain("got ");
        assertThat(result.reason()).doesNotContain("payload");
    }

    @Test
    void kind_isApi() {
        assertThat(runner.kind()).isEqualTo(RunnerKind.API);
    }

    // ---- helpers ----

    private ApiRequestSnapshot spec(String method, Integer expectedStatus, List<ApiAssertion> assertions) {
        return new ApiRequestSnapshot(method, server.url("/").toString(), List.of(), null, expectedStatus,
            null, null, assertions);
    }

    private CaseExecutionContext ctx(ApiRequestSnapshot spec) {
        return ctxWith(spec, CancellationToken.never());
    }

    private CaseExecutionContext ctxWith(ApiRequestSnapshot spec, CancellationToken token) {
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "case", 0, spec);
        var timeout = props.effectiveTimeout(spec.timeoutMillis());
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), executionId, UUID.randomUUID(),
            item, timeout, 1_048_576, token);
    }

    private static WorkerExecutionProperties props(String serverHost) {
        return TestProps.defaults(Mode.REAL, Duration.ofMinutes(5),
            new Ssrf(true, List.of(serverHost, "localhost", "127.0.0.1"), List.of()),
            new Redaction(List.of("authorization", "cookie"), List.of()), false);
    }

    /** Reports not-cancelled for the first {@code falseCalls} invocations, then cancelled. */
    private static final class CountingCancellationToken implements CancellationToken {
        private final int falseCalls;
        private int calls;

        private CountingCancellationToken(int falseCalls) {
            this.falseCalls = falseCalls;
        }

        @Override
        public boolean isCancelled() {
            return calls++ >= falseCalls;
        }
    }
}
