package com.qualityops.worker.execution.application.service;

import com.qualityops.events.ApiAssertion;
import com.qualityops.events.ApiRequestSnapshot;
import com.qualityops.events.BrowserTestSnapshot;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RepositoryProvider;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.adapter.out.runner.BlockedRepositoryRunner;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.support.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRunnerResolverTest {

    private static final ExecutionRunner SIMULATED = fakeRunner(RunnerKind.SIMULATED);
    private static final ExecutionRunner API = fakeRunner(RunnerKind.API);
    private static final ExecutionRunner BROWSER = fakeRunner(RunnerKind.BROWSER);
    private static final ExecutionRunner REPOSITORY = fakeRunner(RunnerKind.REPOSITORY);

    @Test
    void resolve_autoMode_caseWithoutApiRequest_returnsSimulated() {
        var resolver = resolver(Mode.AUTO);
        assertThat(resolver.resolve(plain()).kind()).isEqualTo(RunnerKind.SIMULATED);
    }

    @Test
    void resolve_autoMode_caseWithApiRequest_returnsApi() {
        var resolver = resolver(Mode.AUTO);
        assertThat(resolver.resolve(withApiRequest()).kind()).isEqualTo(RunnerKind.API);
    }

    @Test
    void resolve_simulatedMode_caseWithApiRequest_returnsSimulated() {
        var resolver = resolver(Mode.SIMULATED);
        assertThat(resolver.resolve(withApiRequest()).kind()).isEqualTo(RunnerKind.SIMULATED);
    }

    @Test
    void resolve_realMode_caseWithoutApiRequest_returnsApi() {
        var resolver = resolver(Mode.REAL);
        assertThat(resolver.resolve(plain()).kind()).isEqualTo(RunnerKind.API);
    }

    @Test
    void resolvedKindFor_autoMode_anyRealCase_returnsApi_elseSimulated() {
        var resolver = resolver(Mode.AUTO);
        assertThat(resolver.resolvedKindFor(List.of(plain(), withApiRequest()))).isEqualTo(RunnerKind.API);
        assertThat(resolver.resolvedKindFor(List.of(plain(), plain()))).isEqualTo(RunnerKind.SIMULATED);
    }

    @Test
    void resolve_autoMode_caseWithBrowserTest_returnsBrowser() {
        var resolver = resolver(Mode.AUTO);
        assertThat(resolver.resolve(withBrowserTest()).kind()).isEqualTo(RunnerKind.BROWSER);
    }

    @Test
    void resolve_realMode_caseWithBrowserTest_returnsBrowser() {
        var resolver = resolver(Mode.REAL);
        assertThat(resolver.resolve(withBrowserTest()).kind()).isEqualTo(RunnerKind.BROWSER);
    }

    @Test
    void resolve_realMode_caseWithoutBrowserTest_returnsApi() {
        var resolver = resolver(Mode.REAL);
        assertThat(resolver.resolve(withApiRequest()).kind()).isEqualTo(RunnerKind.API);
    }

    @Test
    void resolve_autoMode_caseWithBothSpecs_logsAndReturnsBrowser() {
        var resolver = resolver(Mode.AUTO);
        var both = new TestCaseSnapshotItem(UUID.randomUUID(), "both", 0,
            new ApiRequestSnapshot("GET", "https://api.example.test/x", List.of(), null, 200, null, null,
                List.<ApiAssertion>of()),
            new BrowserTestSnapshot("https://x.test/", List.of(), List.of(), null, null, null));
        assertThat(resolver.resolve(both).kind()).isEqualTo(RunnerKind.BROWSER);
    }

    @Test
    void resolvedKindFor_autoMode_anyBrowserCase_returnsBrowser() {
        var resolver = resolver(Mode.AUTO);
        assertThat(resolver.resolvedKindFor(List.of(plain(), withApiRequest(), withBrowserTest())))
            .isEqualTo(RunnerKind.BROWSER);
    }

    @Test
    void resolvedKindFor_realMode_anyBrowserCase_returnsBrowser_elseApi() {
        var resolver = resolver(Mode.REAL);
        assertThat(resolver.resolvedKindFor(List.of(plain(), withBrowserTest()))).isEqualTo(RunnerKind.BROWSER);
        assertThat(resolver.resolvedKindFor(List.of(plain(), withApiRequest()))).isEqualTo(RunnerKind.API);
    }

    @Test
    void resolve_repoTestCase_winsOverBrowserAndApi_regardlessOfMode() {
        for (Mode mode : Mode.values()) {
            var resolver = new ExecutionRunnerResolver(TestProps.defaults(mode),
                List.of(SIMULATED, API, BROWSER, REPOSITORY), new SimpleMeterRegistry());
            assertThat(resolver.resolve(withRepoTest()).kind()).isEqualTo(RunnerKind.REPOSITORY);
        }
    }

    @Test
    void resolve_repoTestCase_noRepositoryRunnerRegistered_returnsBlockedSentinel_noNpe() {
        var resolver = resolver(Mode.AUTO); // SIMULATED, API, BROWSER only — no REPOSITORY

        var runner = resolver.resolve(withRepoTest());

        assertThat(runner).isInstanceOf(BlockedRepositoryRunner.class);
        assertThat(runner.kind()).isEqualTo(RunnerKind.REPOSITORY);
        var ctx = new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), withRepoTest(), Duration.ofSeconds(30), 0, () -> false);
        assertThat(runner.execute(ctx).status()).isEqualTo(CaseStatus.BLOCKED);
    }

    @Test
    void resolvedKindFor_anyRepoCase_returnsRepository() {
        var resolver = resolver(Mode.AUTO);
        assertThat(resolver.resolvedKindFor(List.of(plain(), withBrowserTest(), withRepoTest())))
            .isEqualTo(RunnerKind.REPOSITORY);
    }

    private ExecutionRunnerResolver resolver(Mode mode) {
        return new ExecutionRunnerResolver(TestProps.defaults(mode), List.of(SIMULATED, API, BROWSER),
            new SimpleMeterRegistry());
    }

    private static TestCaseSnapshotItem plain() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "plain", 0);
    }

    private static TestCaseSnapshotItem withApiRequest() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "real", 0,
            new ApiRequestSnapshot("GET", "https://api.example.test/x", List.of(), null, 200, null, null,
                List.<ApiAssertion>of()));
    }

    private static TestCaseSnapshotItem withBrowserTest() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "b", 0, null,
            new BrowserTestSnapshot("https://x.test/", List.of(), List.of(), null, null, null));
    }

    private static TestCaseSnapshotItem withRepoTest() {
        var snapshot = new RepoTestSnapshot(UUID.randomUUID(), RepositoryProvider.GITHUB, "github.com",
            "acme/web", "main", "0123456789abcdef0123456789abcdef01234567", RepoRefType.BRANCH,
            FrameworkPreset.PYTEST, "python:3.12-slim@sha256:"
                + "1111111111111111111111111111111111111111111111111111111111111111",
            null, List.of("pytest"), RepoReportFormat.JUNIT_XML, List.of("report.xml"), List.of(),
            List.of(), List.of(), null, RepoResourceProfile.SMALL, RepoNetworkPolicy.ISOLATED, 600);
        return new TestCaseSnapshotItem(UUID.randomUUID(), "repo", 0, null, null, snapshot);
    }

    private static ExecutionRunner fakeRunner(RunnerKind kind) {
        return new ExecutionRunner() {
            @Override
            public RunnerKind kind() {
                return kind;
            }

            @Override
            public CaseExecutionResult execute(CaseExecutionContext ctx) {
                throw new UnsupportedOperationException("not exercised");
            }
        };
    }
}
