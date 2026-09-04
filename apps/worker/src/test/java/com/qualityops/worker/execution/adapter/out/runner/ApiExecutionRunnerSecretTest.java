package com.qualityops.worker.execution.adapter.out.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.ApiRequestSnapshot;
import com.qualityops.events.HttpHeader;
import com.qualityops.events.SecretRef;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.support.StubSecretResolver;
import com.qualityops.worker.support.TestProps;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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

@Timeout(20)
class ApiExecutionRunnerSecretTest {

    private static final String PLAINTEXT = "s3cr3t-token-value";

    private MockWebServer server;
    private ApiExecutionRunner runner;
    private WorkerExecutionProperties props;
    private final UUID executionId = UUID.randomUUID();
    private final StubSecretResolver secrets = new StubSecretResolver().with("API_TOKEN", PLAINTEXT);

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        props = TestProps.defaults(Mode.REAL, Duration.ofMinutes(5),
            new Ssrf(true, List.of(server.url("/").host(), "localhost", "127.0.0.1"), List.of()),
            new Redaction(List.of("authorization", "cookie"), List.of()), false);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        var redactor = new Redactor(props);
        runner = new ApiExecutionRunner(client, new TargetValidator(props), redactor,
            new AssertionEvaluator(new ObjectMapper(), redactor, props), props, secrets);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void secretRefHeader_resolvedValueSentButAlwaysMaskedInRequestMetadata() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        // X-Custom-Auth is NOT on the redaction denylist — masking here proves the hard rule.
        var spec = new ApiRequestSnapshot("GET", server.url("/").toString(),
            List.of(new HttpHeader("X-Custom-Auth", null, new SecretRef("API_TOKEN"))),
            null, 200, null, null, List.of());

        var result = runner.execute(ctx(spec));

        var recorded = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader("X-Custom-Auth")).isEqualTo(PLAINTEXT);

        assertThat(result.request().redactedHeaders().get("X-Custom-Auth")).isEqualTo("***");
        assertThat(result.request().redactedHeaders().toString()).doesNotContain(PLAINTEXT);
        assertThat(result.toString()).doesNotContain(PLAINTEXT);
        assertThat(secrets.requestedKeys()).containsExactly("API_TOKEN");
    }

    @Test
    void unresolvableSecretRef_blocksCase_andNeverSendsRequest() {
        var spec = new ApiRequestSnapshot("GET", server.url("/").toString(),
            List.of(new HttpHeader("Authorization", null, new SecretRef("MISSING_KEY"))),
            null, 200, null, null, List.of());

        var result = runner.execute(ctx(spec));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(result.reason()).isEqualTo("unresolved secret reference: MISSING_KEY");
        assertThat(server.getRequestCount()).isZero();
    }

    private CaseExecutionContext ctx(ApiRequestSnapshot spec) {
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "case", 0, spec);
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), executionId, UUID.randomUUID(),
            item, props.effectiveTimeout(spec.timeoutMillis()), 1_048_576, CancellationToken.never());
    }
}
