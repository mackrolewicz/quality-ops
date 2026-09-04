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
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.support.StubSecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ADR-009 §10 — a cooperative cancel mid-run (the framework container reports
 *  {@code cancelled=true}) ends the case {@code ERROR "run cancelled"} with
 *  {@code SideEffectClass.POSSIBLE} (the command had already started), and
 *  {@code containerRunner.cleanup(executionId)} always runs. */
class RepositoryCancellationTest {

    private RepoExecWorkerProperties props(Path workspaceRoot) {
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
            null, List.of("pytest"), RepoReportFormat.JUNIT_XML, List.of("report.xml"), List.of(), List.of(),
            List.of(), null, RepoResourceProfile.SMALL, RepoNetworkPolicy.ISOLATED, 60);
    }

    @Test
    void frameworkContainerReportsCancelled_endsErrorRunCancelled_sideEffectPossible_cleanupAlwaysRuns(
            @TempDir Path workspaceRoot) {
        var containerRunner = Mockito.mock(ContainerRunnerPort.class);
        var redactor = new Redactor(com.qualityops.worker.support.TestProps.defaults(
            com.qualityops.worker.config.WorkerExecutionProperties.Mode.AUTO, Duration.ofMinutes(5),
            null, new Redaction(List.of(), List.of()), false));
        var registry = new ReportParserRegistry(List.of(new JUnitXmlReportParser()));
        var runner = new RepositoryExecutionRunner(containerRunner, props(workspaceRoot),
            new StubSecretResolver(), redactor, registry, new WorkspacePathResolver(),
            new RepoExecMetrics(new SimpleMeterRegistry()));

        var now = Instant.now();
        var checkoutOk = new ContainerRunResult(0, false, false, now, now.plusSeconds(1));
        var frameworkCancelled = new ContainerRunResult(143, false, true, now, now.plusSeconds(2));
        when(containerRunner.run(any(), any(), any())).thenReturn(checkoutOk, frameworkCancelled);

        var eid = UUID.randomUUID();
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "repo case", 0, null, null, snapshot());
        var ctx = new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), eid, UUID.randomUUID(),
            item, Duration.ofSeconds(30), 0, CancellationToken.never(), 0);

        var result = runner.execute(ctx);

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(result.reason()).isEqualTo("run cancelled");
        assertThat(result.sideEffectClass()).isEqualTo(SideEffectClass.POSSIBLE);
        Mockito.verify(containerRunner, Mockito.times(1)).cleanup(eid);
    }

    @Test
    void checkoutContainerReportsCancelled_endsErrorRunCancelled_sideEffectNoneObserved(
            @TempDir Path workspaceRoot) {
        var containerRunner = Mockito.mock(ContainerRunnerPort.class);
        var redactor = new Redactor(com.qualityops.worker.support.TestProps.defaults(
            com.qualityops.worker.config.WorkerExecutionProperties.Mode.AUTO, Duration.ofMinutes(5),
            null, new Redaction(List.of(), List.of()), false));
        var registry = new ReportParserRegistry(List.of(new JUnitXmlReportParser()));
        var runner = new RepositoryExecutionRunner(containerRunner, props(workspaceRoot),
            new StubSecretResolver(), redactor, registry, new WorkspacePathResolver(),
            new RepoExecMetrics(new SimpleMeterRegistry()));

        var now = Instant.now();
        var checkoutCancelled = new ContainerRunResult(143, false, true, now, now.plusSeconds(1));
        when(containerRunner.run(any(), any(), any())).thenReturn(checkoutCancelled);

        var eid = UUID.randomUUID();
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "repo case", 0, null, null, snapshot());
        var ctx = new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), eid, UUID.randomUUID(),
            item, Duration.ofSeconds(30), 0, CancellationToken.never(), 0);

        var result = runner.execute(ctx);

        assertThat(result.status()).isEqualTo(CaseStatus.ERROR);
        assertThat(result.reason()).isEqualTo("run cancelled");
        assertThat(result.sideEffectClass()).isEqualTo(SideEffectClass.NONE_OBSERVED);
        Mockito.verify(containerRunner, Mockito.times(1)).run(any(), any(), any());
        Mockito.verify(containerRunner, Mockito.times(1)).cleanup(eid);
    }
}
