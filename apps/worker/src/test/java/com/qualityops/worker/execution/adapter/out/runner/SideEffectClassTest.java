package com.qualityops.worker.execution.adapter.out.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.ApiRequestSnapshot;
import com.qualityops.events.BrowserAssertion;
import com.qualityops.events.BrowserStep;
import com.qualityops.events.BrowserTestSnapshot;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Browser;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import com.qualityops.worker.execution.application.port.out.PlaywrightBrowser;
import com.qualityops.worker.execution.domain.BrowserRunOutcome;
import com.qualityops.worker.execution.domain.BrowserRunOutcome.Status;
import com.qualityops.worker.execution.domain.BrowserStepOutcome;
import com.qualityops.worker.execution.domain.BrowserStepStatus;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.support.StubSecretResolver;
import com.qualityops.worker.support.TestProps;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ADR-005 §3.3: which failures may be safely retried. */
@ExtendWith(MockitoExtension.class)
@Timeout(20)
class SideEffectClassTest {

    // ---- API runner ----

    private MockWebServer server;
    private ApiExecutionRunner apiRunner;
    private WorkerExecutionProperties apiProps;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        apiProps = TestProps.defaults(Mode.REAL, Duration.ofMinutes(5),
            new Ssrf(true, List.of(server.url("/").host(), "localhost", "127.0.0.1"), List.of()),
            new Redaction(List.of("authorization"), List.of()), false);
        var redactor = new Redactor(apiProps);
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        apiRunner = new ApiExecutionRunner(client, new TargetValidator(apiProps), redactor,
            new AssertionEvaluator(new ObjectMapper(), redactor, apiProps), apiProps,
            new StubSecretResolver());
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void api_getTimeoutWithNoStatusLine_isNoneObserved() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var spec = new ApiRequestSnapshot("GET", server.url("/").toString(), List.of(), null, 200,
            150, null, List.of());

        var r = apiRunner.execute(apiCtx(spec));

        assertThat(r.status()).isEqualTo(CaseStatus.TIMEOUT);
        assertThat(r.sideEffectClass()).isEqualTo(SideEffectClass.NONE_OBSERVED);
    }

    @Test
    void api_postTimeoutWithNoStatusLine_isPossible() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var spec = new ApiRequestSnapshot("POST", server.url("/").toString(), List.of(), "{}", 200,
            150, null, List.of());

        var r = apiRunner.execute(apiCtx(spec));

        assertThat(r.status()).isEqualTo(CaseStatus.TIMEOUT);
        assertThat(r.sideEffectClass()).isEqualTo(SideEffectClass.POSSIBLE);
    }

    @Test
    void api_dnsFailure_isNoneObserved() {
        var spec = new ApiRequestSnapshot("POST", "http://localhost:1/never", List.of(), "{}", 200,
            500, null, List.of());

        var r = apiRunner.execute(apiCtx(spec));

        assertThat(r.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(r.sideEffectClass()).isEqualTo(SideEffectClass.NONE_OBSERVED);
    }

    @Test
    void api_responseStatusSeenThenFailedAssertion_isPossible() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        var spec = new ApiRequestSnapshot("GET", server.url("/").toString(), List.of(), null, 200,
            2000, null, List.of());

        var r = apiRunner.execute(apiCtx(spec));

        assertThat(r.status()).isEqualTo(CaseStatus.FAILED);
        assertThat(r.sideEffectClass()).isEqualTo(SideEffectClass.POSSIBLE);
    }

    private CaseExecutionContext apiCtx(ApiRequestSnapshot spec) {
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "case", 0, spec);
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), item, apiProps.effectiveTimeout(spec.timeoutMillis()), 1_048_576,
            CancellationToken.never());
    }

    // ---- browser runner ----

    @Mock private PlaywrightBrowser driver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void browser_timeoutWithZeroInteractiveSteps_isNoneObserved() {
        when(driver.run(any())).thenReturn(new BrowserRunOutcome(
            List.of(new BrowserStepOutcome(0, BrowserStep.Action.NAVIGATE, "(no selector)",
                BrowserStepStatus.TIMEOUT, 10L, "nav timed out")),
            List.of(), "about:blank", null, 0L, null, 0L, Status.TIMED_OUT, null));

        var r = browserRunner().execute(browserCtx());

        assertThat(r.status()).isEqualTo(CaseStatus.TIMEOUT);
        assertThat(r.sideEffectClass()).isEqualTo(SideEffectClass.NONE_OBSERVED);
    }

    @Test
    void browser_errorAfterInteractiveClickStep_isPossible() {
        when(driver.run(any())).thenReturn(new BrowserRunOutcome(
            List.of(
                new BrowserStepOutcome(0, BrowserStep.Action.CLICK, "role=button", BrowserStepStatus.PASSED, 5L, null),
                new BrowserStepOutcome(1, BrowserStep.Action.CLICK, "role=submit", BrowserStepStatus.ERROR, 8L, "boom")),
            List.of(), "http://127.0.0.1/", null, 0L, null, 0L, Status.FAULT, null));

        var r = browserRunner().execute(browserCtx());

        assertThat(r.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(r.sideEffectClass()).isEqualTo(SideEffectClass.POSSIBLE);
    }

    private BrowserExecutionRunner browserRunner() {
        var browser = new Browser(true, true,
            Duration.ofSeconds(60), Duration.ofMinutes(3), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(2),
            false, true, true,
            System.getProperty("java.io.tmpdir") + "/qualityops-browser-set", 5_242_880L, Duration.ofHours(1));
        var props = TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5),
            new Ssrf(true, List.of("127.0.0.1", "localhost"), List.of()),
            new Redaction(List.of(), List.of()), false, browser);
        return new BrowserExecutionRunner(driver, new TargetValidator(props), new Redactor(props),
            props, executor, new StubSecretResolver());
    }

    private CaseExecutionContext browserCtx() {
        var spec = new BrowserTestSnapshot("http://127.0.0.1/",
            List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, "http://127.0.0.1/", null)),
            List.<BrowserAssertion>of(), null, null, null);
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "browser", 0, null, spec);
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), item, Duration.ofSeconds(60), 1_048_576, CancellationToken.never());
    }
}
