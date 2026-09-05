package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RepositoryProvider;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.RepoExecMetrics;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import com.qualityops.worker.config.RepoExecWorkerProperties.Container;
import com.qualityops.worker.config.RepoExecWorkerProperties.Docker;
import com.qualityops.worker.config.RepoExecWorkerProperties.Images;
import com.qualityops.worker.config.RepoExecWorkerProperties.Network;
import com.qualityops.worker.config.RepoExecWorkerProperties.Profile;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunResult;
import com.qualityops.worker.execution.application.service.ReportParserRegistry;
import com.qualityops.worker.execution.adapter.out.runner.report.JUnitXmlReportParser;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.support.StubSecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ADR-009 §7 — run-level {@code CaseStatus} mapping: PASSED iff container
 *  exit 0 AND zero FAILED/ERROR items; else FAILED/TIMEOUT per the container
 *  outcome. Uses a mocked {@link ContainerRunnerPort} + the real
 *  {@link JUnitXmlReportParser} against a real temp workspace. */
class RepositoryRunResultMappingTest {

    private ContainerRunnerPort containerRunner;
    private RepositoryExecutionRunner runner;
    private Path workspaceRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.workspaceRoot = tempDir;
        this.containerRunner = Mockito.mock(ContainerRunnerPort.class);
        var redactor = new Redactor(com.qualityops.worker.support.TestProps.defaults(
            com.qualityops.worker.config.WorkerExecutionProperties.Mode.AUTO, Duration.ofMinutes(5),
            null, new Redaction(List.of(), List.of()), false));
        var registry = new ReportParserRegistry(List.of(new JUnitXmlReportParser()));
        runner = new RepositoryExecutionRunner(containerRunner, props(), new StubSecretResolver(), redactor,
            registry, new WorkspacePathResolver(), new RepoExecMetrics(new io.micrometer.core.instrument
            .simple.SimpleMeterRegistry()));
    }

    private RepoExecWorkerProperties props() {
        return new RepoExecWorkerProperties(true,
            new Images("pw@sha256:x", "j@sha256:x",
                "python:3.12-slim@sha256:"
                    + "1111111111111111111111111111111111111111111111111111111111111111",
                "cy@sha256:x", "k6@sha256:x", "alpine/git@sha256:x"),
            false, Duration.ofMinutes(10), Duration.ofMinutes(30), RepoResourceProfile.SMALL,
            new Docker("tcp://docker-proxy:2375", true),
            new Container(12000, 12000, 512, 256, 2048, Duration.ofSeconds(5), 4096, 8192),
            new Network("none", "egress"), Map.of("small", new Profile(1, 1024)),
            workspaceRoot.toString(), 20_971_520L, 4096, true, false,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(10), "S_");
    }

    private RepoTestSnapshot snapshot() {
        return new RepoTestSnapshot(UUID.randomUUID(), RepositoryProvider.GITHUB, "github.com", "acme/web",
            "main", "0123456789abcdef0123456789abcdef01234567", RepoRefType.BRANCH, FrameworkPreset.PYTEST,
            "python:3.12-slim@sha256:"
                + "1111111111111111111111111111111111111111111111111111111111111111",
            null, List.of("pytest", "--junitxml=report.xml"), RepoReportFormat.JUNIT_XML,
            List.of("report.xml"), List.of(), List.of(), List.of(), null, RepoResourceProfile.SMALL,
            RepoNetworkPolicy.ISOLATED, 60);
    }

    private CaseExecutionContext ctx(UUID executionId, int attemptEpoch) {
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "repo case", 0, null, null, snapshot());
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), executionId, UUID.randomUUID(),
            item, Duration.ofSeconds(60), 0, CancellationToken.never(), attemptEpoch);
    }

    private void writeReport(UUID executionId, int attemptEpoch, String xml) throws IOException {
        Path dir = workspaceRoot.resolve(executionId.toString()).resolve(String.valueOf(attemptEpoch));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.xml"), xml);
    }

    private ContainerRunResult ok(Instant t) {
        return new ContainerRunResult(0, false, false, t, t.plusSeconds(1));
    }

    @Test
    void exitZero_zeroFailedItems_isPassed() throws IOException {
        var eid = UUID.randomUUID();
        writeReport(eid, 0, "<testsuite><testcase classname=\"t\" name=\"ok\" time=\"0.1\"/></testsuite>");
        var now = Instant.now();
        when(containerRunner.run(any(), any(), any())).thenReturn(ok(now), ok(now));

        var result = runner.execute(ctx(eid, 0));

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
        assertThat(result.repository().items()).hasSize(1);
        assertThat(result.repository().provenance().exitCode()).isZero();
    }

    @Test
    void exitNonZero_isFailed() throws IOException {
        var eid = UUID.randomUUID();
        writeReport(eid, 0, "<testsuite><testcase classname=\"t\" name=\"ok\" time=\"0.1\">"
            + "<failure message=\"boom\" type=\"AssertionError\"/></testcase></testsuite>");
        var now = Instant.now();
        var checkoutOk = ok(now);
        var frameworkFailed = new ContainerRunResult(1, false, false, now, now.plusSeconds(1));
        when(containerRunner.run(any(), any(), any())).thenReturn(checkoutOk, frameworkFailed);

        var result = runner.execute(ctx(eid, 0));

        assertThat(result.status()).isEqualTo(CaseStatus.FAILED);
        assertThat(result.reason()).contains("1 of 1 tests failed");
    }

    @Test
    void frameworkTimedOut_isTimeout() throws IOException {
        var eid = UUID.randomUUID();
        writeReport(eid, 0, "<testsuite><testcase classname=\"t\" name=\"ok\" time=\"0.1\"/></testsuite>");
        var now = Instant.now();
        var checkoutOk = ok(now);
        var frameworkTimedOut = new ContainerRunResult(137, true, false, now, now.plusSeconds(60));
        when(containerRunner.run(any(), any(), any())).thenReturn(checkoutOk, frameworkTimedOut);

        var result = runner.execute(ctx(eid, 0));

        assertThat(result.status()).isEqualTo(CaseStatus.TIMEOUT);
    }

    @Test
    void checkoutFails_isErrorAndFrameworkNeverRuns() {
        var eid = UUID.randomUUID();
        var now = Instant.now();
        var checkoutFailed = new ContainerRunResult(1, false, false, now, now.plusSeconds(1));
        when(containerRunner.run(any(), any(), any())).thenReturn(checkoutFailed);

        var result = runner.execute(ctx(eid, 0));

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(result.sideEffectClass()).isEqualTo(
            com.qualityops.worker.execution.domain.SideEffectClass.NONE_OBSERVED);
        Mockito.verify(containerRunner, Mockito.times(1)).run(any(), any(), any());
    }

    @Test
    void everyExecution_callsCleanupExactlyOnce() throws IOException {
        var eid = UUID.randomUUID();
        writeReport(eid, 0, "<testsuite><testcase classname=\"t\" name=\"ok\" time=\"0.1\"/></testsuite>");
        var now = Instant.now();
        when(containerRunner.run(any(), any(), any())).thenReturn(ok(now), ok(now));

        runner.execute(ctx(eid, 0));

        Mockito.verify(containerRunner, Mockito.times(1)).cleanup(eid);
    }
}
