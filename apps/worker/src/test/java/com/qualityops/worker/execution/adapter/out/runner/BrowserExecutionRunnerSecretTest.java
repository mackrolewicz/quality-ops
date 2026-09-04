package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.events.BrowserAssertion;
import com.qualityops.events.BrowserStep;
import com.qualityops.events.BrowserTestSnapshot;
import com.qualityops.events.Selector;
import com.qualityops.events.SecretRef;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Browser;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import com.qualityops.worker.execution.application.port.out.PlaywrightBrowser;
import com.qualityops.worker.execution.domain.BrowserRunCommand;
import com.qualityops.worker.execution.domain.BrowserRunOutcome;
import com.qualityops.worker.execution.domain.BrowserRunOutcome.Status;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.support.StubSecretResolver;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Timeout(20)
class BrowserExecutionRunnerSecretTest {

    private static final String PLAINTEXT = "hunter2-plaintext";

    @Mock private PlaywrightBrowser driver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final StubSecretResolver secrets = new StubSecretResolver().with("DEMO_PASSWORD", PLAINTEXT);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void fillStepWithSecretValue_commandFlagsSecretCase_andForcesTraceOff() {
        when(driver.run(any())).thenReturn(new BrowserRunOutcome(List.of(), List.of(),
            "http://127.0.0.1/", null, 0L, null, 0L, Status.COMPLETED, null));
        var captor = ArgumentCaptor.forClass(BrowserRunCommand.class);

        // captureTrace = true in config — a secret-bearing case must still send it as false.
        var result = runner(traceOnProps()).execute(ctx(fillSecretSpec()));

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
        org.mockito.Mockito.verify(driver).run(captor.capture());
        assertThat(captor.getValue().secretCase()).isTrue();
        assertThat(captor.getValue().captureTrace()).isFalse();
        assertThat(result.toString()).doesNotContain(PLAINTEXT);
    }

    @Test
    void unresolvableSecretValue_blocksCase_andNeverRunsTheDriver() {
        var spec = new BrowserTestSnapshot("http://127.0.0.1/",
            List.of(new BrowserStep(BrowserStep.Action.FILL,
                new Selector(Selector.Strategy.LABEL, "Password", null, null),
                null, null, new SecretRef("MISSING"))),
            List.<BrowserAssertion>of(), null, null, null);

        var result = runner(traceOnProps()).execute(ctx(spec));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(result.reason()).isEqualTo("unresolved secret reference: MISSING");
        verifyNoInteractions(driver);
    }

    private BrowserTestSnapshot fillSecretSpec() {
        return new BrowserTestSnapshot("http://127.0.0.1/",
            List.of(
                new BrowserStep(BrowserStep.Action.NAVIGATE, null, "http://127.0.0.1/", null),
                new BrowserStep(BrowserStep.Action.FILL,
                    new Selector(Selector.Strategy.LABEL, "Password", null, null),
                    null, null, new SecretRef("DEMO_PASSWORD"))),
            List.<BrowserAssertion>of(), null, null, null);
    }

    private BrowserExecutionRunner runner(WorkerExecutionProperties props) {
        return new BrowserExecutionRunner(driver, new TargetValidator(props), new Redactor(props),
            props, executor, secrets);
    }

    private static WorkerExecutionProperties traceOnProps() {
        var browser = new Browser(true, true,
            Duration.ofSeconds(60), Duration.ofMinutes(3), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(2),
            true /* captureTrace */, true, true,
            System.getProperty("java.io.tmpdir") + "/qualityops-browser-secret", 5_242_880L, Duration.ofHours(1));
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5),
            new Ssrf(true, List.of("127.0.0.1", "localhost"), List.of()),
            new Redaction(List.of(), List.of()), false, browser);
    }

    private CaseExecutionContext ctx(BrowserTestSnapshot spec) {
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "browser", 0, null, spec);
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), item, Duration.ofSeconds(60), 1_048_576, CancellationToken.never());
    }
}
