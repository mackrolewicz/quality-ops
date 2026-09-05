package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.events.BrowserAssertion;
import com.qualityops.events.BrowserStep;
import com.qualityops.events.BrowserTestSnapshot;
import com.qualityops.events.Selector;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Browser;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import com.qualityops.worker.execution.application.port.out.PlaywrightBrowser;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.BrowserAssertionOutcome;
import com.qualityops.worker.execution.domain.BrowserRunCommand;
import com.qualityops.worker.execution.domain.BrowserRunOutcome;
import com.qualityops.worker.execution.domain.BrowserRunOutcome.Status;
import com.qualityops.worker.execution.domain.BrowserStepOutcome;
import com.qualityops.worker.execution.domain.BrowserStepStatus;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Timeout(20)
class BrowserExecutionRunnerTest {

    @Mock
    private PlaywrightBrowser driver;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private final com.qualityops.worker.support.StubSecretResolver secretResolver =
        new com.qualityops.worker.support.StubSecretResolver();

    private BrowserExecutionRunner runner(WorkerExecutionProperties props) {
        var validator = new TargetValidator(props);
        var redactor = new Redactor(props);
        return new BrowserExecutionRunner(driver, validator, redactor, props, executor, secretResolver);
    }

    private static Browser browser(boolean enabled, Duration hardKillGrace) {
        return new Browser(enabled, true,
            Duration.ofSeconds(60), Duration.ofMinutes(3), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofSeconds(30), hardKillGrace,
            false, true, true,
            System.getProperty("java.io.tmpdir") + "/qualityops-browser-test", 5_242_880L, Duration.ofHours(1));
    }

    private static WorkerExecutionProperties props(Browser browser) {
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5),
            new Ssrf(true, List.of("127.0.0.1", "localhost"), List.of()),
            new Redaction(List.of(), List.of()), false, browser);
    }

    private CaseExecutionContext ctx(BrowserTestSnapshot spec, Duration effectiveTimeout, CancellationToken token) {
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "browser", 0, null, spec);
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), item, effectiveTimeout, 1_048_576, token);
    }

    private static BrowserTestSnapshot spec(String startUrl, List<BrowserStep> steps,
                                            List<BrowserAssertion> assertions,
                                            Integer stepTimeout) {
        return new BrowserTestSnapshot(startUrl, steps, assertions, null, stepTimeout, null);
    }

    private static BrowserRunOutcome completed(boolean allPassed) {
        List<BrowserAssertionOutcome> asserts = allPassed ? List.of()
            : List.of(new BrowserAssertionOutcome(BrowserAssertion.Type.VISIBLE, "testId=msg", "", "false", false));
        return new BrowserRunOutcome(List.of(), asserts, "http://127.0.0.1/", null, 0L, null, 0L,
            Status.COMPLETED, null);
    }

    @Test
    void execute_driverReturnsCompletedAllPassed_returnsPassed() {
        when(driver.run(any())).thenReturn(completed(true));

        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
        assertThat(result.browser()).isNotNull();
        assertThat(result.browser().finalUrl()).isEqualTo("http://127.0.0.1/");
    }

    @Test
    void execute_driverReturnsCompletedWithFailedAssertion_returnsFailed() {
        when(driver.run(any())).thenReturn(completed(false));

        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.FAILED);
        assertThat(result.reason()).contains("VISIBLE").doesNotContain("false");
    }

    @Test
    void execute_driverReturnsFault_returnsError_andForceRecycled() {
        when(driver.run(any())).thenThrow(new IllegalStateException("driver crashed"));

        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        verify(driver).forceRecycle();
    }

    @Test
    void execute_faultOutcome_reasonIsGeneric_doesNotLeakUrlOrToken() {
        when(driver.run(any())).thenReturn(new BrowserRunOutcome(List.of(), List.of(), "about:blank",
            null, 0L, null, 0L, Status.FAULT,
            "net::ERR_ABORTED at https://internal-host.corp/login?token=abc123SECRET"));

        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(result.reason())
            .doesNotContain("abc123SECRET")
            .doesNotContain("internal-host.corp")
            .isEqualTo("browser navigation or driver fault");
    }

    @Test
    void execute_driverHangsPastBudget_returnsTimeout_andForceRecycled() {
        when(driver.run(any())).thenAnswer(inv -> {
            Thread.sleep(4_000);
            return completed(true);
        });

        long start = System.nanoTime();
        var result = runner(props(browser(true, Duration.ofMillis(300))))
            .execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), null),
                Duration.ofMillis(300), CancellationToken.never()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(CaseStatus.TIMEOUT);
        verify(driver).forceRecycle();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void execute_cancelledBeforeSubmit_returnsError() {
        var r = runner(props(browser(true, Duration.ofSeconds(30))));

        var result = r.execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), null),
            Duration.ofSeconds(60), () -> true));

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(result.reason()).isEqualTo("run cancelled");
        verifyNoInteractions(driver);
    }

    @Test
    void execute_distinctCommandPerExecute() {
        when(driver.run(any())).thenReturn(completed(true));
        var r = runner(props(browser(true, Duration.ofSeconds(5))));

        r.execute(ctx(spec("http://127.0.0.1/a", List.of(), List.of(), null),
            Duration.ofSeconds(60), CancellationToken.never()));
        r.execute(ctx(spec("http://127.0.0.1/b", List.of(), List.of(), null),
            Duration.ofSeconds(60), CancellationToken.never()));

        var captor = ArgumentCaptor.forClass(BrowserRunCommand.class);
        verify(driver, org.mockito.Mockito.times(2)).run(captor.capture());
        var cmds = captor.getAllValues();
        assertThat(cmds.get(0).startUrl()).isEqualTo("http://127.0.0.1/a");
        assertThat(cmds.get(1).startUrl()).isEqualTo("http://127.0.0.1/b");
        assertThat(cmds.get(0).caseId()).isNotEqualTo(cmds.get(1).caseId());
    }

    @Test
    void execute_startUrlDenied_returnsBlocked_driverNeverCalled() {
        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://169.254.169.254/latest/meta-data", List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        verifyNoInteractions(driver);
    }

    @Test
    void execute_navigateStepUrlDenied_returnsBlocked_driverNeverCalled() {
        var steps = List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null,
            "http://169.254.169.254/latest/meta-data", null));
        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://127.0.0.1/", steps, List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        verifyNoInteractions(driver);
    }

    @Test
    void execute_nullStartUrl_returnsBlocked_notThrown() {
        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec(null, List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        verifyNoInteractions(driver);
    }

    @Test
    void execute_startUrlHasUserinfo_returnsBlocked() {
        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("https://user:pw@example.test/", List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        verifyNoInteractions(driver);
    }

    @Test
    void execute_browserDisabled_returnsBlocked() {
        var result = runner(props(browser(false, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        verifyNoInteractions(driver);
    }

    @Test
    void execute_noBrowserSpec_returnsBlocked() {
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "x", 0, null, null);
        var context = new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), item, Duration.ofSeconds(60), 1_048_576, CancellationToken.never());

        var result = runner(props(browser(true, Duration.ofSeconds(5)))).execute(context);

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        verifyNoInteractions(driver);
    }

    @Test
    void execute_stepTimeoutClampedToTestTimeout() {
        when(driver.run(any())).thenReturn(completed(true));
        var r = runner(props(browser(true, Duration.ofSeconds(5))));

        r.execute(ctx(spec("http://127.0.0.1/", List.of(), List.of(), 999_999_999),
            Duration.ofSeconds(60), CancellationToken.never()));

        var captor = ArgumentCaptor.forClass(BrowserRunCommand.class);
        verify(driver).run(captor.capture());
        assertThat(captor.getValue().stepTimeoutMillis()).isEqualTo(60_000L);
    }

    @Test
    void execute_fillValue_notEchoedInReason() {
        var badStep = new BrowserStepOutcome(0, BrowserStep.Action.FILL, "label=Email",
            BrowserStepStatus.ERROR, 5L, "SECRET123 leaked from the driver message");
        when(driver.run(any())).thenReturn(new BrowserRunOutcome(List.of(badStep), List.of(),
            "http://127.0.0.1/", null, 0L, null, 0L, Status.COMPLETED, null));

        var steps = List.of(new BrowserStep(BrowserStep.Action.FILL,
            new Selector(Selector.Strategy.LABEL, "Email", null, null), "SECRET123", null));
        var result = runner(props(browser(true, Duration.ofSeconds(5))))
            .execute(ctx(spec("http://127.0.0.1/", steps, List.of(), null),
                Duration.ofSeconds(60), CancellationToken.never()));

        assertThat(result.status()).isEqualTo(CaseStatus.FAILED);
        assertThat(result.reason()).doesNotContain("SECRET123");
    }

    @Test
    void kind_isBrowser() {
        assertThat(runner(props(browser(true, Duration.ofSeconds(5)))).kind()).isEqualTo(RunnerKind.BROWSER);
    }
}
