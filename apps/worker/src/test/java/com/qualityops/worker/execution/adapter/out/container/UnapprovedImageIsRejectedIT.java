package com.qualityops.worker.execution.adapter.out.container;

import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RepositoryProvider;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.RepoExecMetrics;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.execution.adapter.out.runner.RepositoryExecutionRunner;
import com.qualityops.worker.execution.adapter.out.runner.Redactor;
import com.qualityops.worker.execution.adapter.out.runner.WorkspacePathResolver;
import com.qualityops.worker.execution.adapter.out.runner.report.JUnitXmlReportParser;
import com.qualityops.worker.execution.application.service.ReportParserRegistry;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.support.StubSecretResolver;
import com.qualityops.worker.support.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-009 §5/§9 ({@code @Tag("docker")}) — a framework image not byte-equal to
 * the allowlist is rejected before any container is created (checkout, using
 * the real digest-pinned {@code alpine/git}, still succeeds first). The
 * digest-mismatch branch itself is unit-tested in {@code RepoImageAllowlistTest}
 * ({@code assertDigestMatches}) — content-addressed pulls make a live mismatch
 * impractical to stage against a real daemon.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class UnapprovedImageIsRejectedIT extends AbstractDockerRunnerIT {

    private RepositoryExecutionRunner newRunner() {
        var redactor = new Redactor(TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), null,
            new Redaction(List.of(), List.of()), false));
        return new RepositoryExecutionRunner(runner(), props(), new StubSecretResolver(), redactor,
            new ReportParserRegistry(List.of(new JUnitXmlReportParser())), new WorkspacePathResolver(),
            new RepoExecMetrics(new SimpleMeterRegistry()));
    }

    private RepoTestSnapshot snapshot(String imageRef) {
        return new RepoTestSnapshot(UUID.randomUUID(), RepositoryProvider.GITHUB, "github.com", "acme/web",
            "main", "0123456789abcdef0123456789abcdef01234567", RepoRefType.BRANCH, FrameworkPreset.PYTEST,
            imageRef, null, List.of("pytest"), RepoReportFormat.JUNIT_XML, List.of("report.xml"), List.of(),
            List.of(), List.of(), null, RepoResourceProfile.SMALL, RepoNetworkPolicy.ISOLATED, 30);
    }

    @Test
    void frameworkImageNotOnAllowlist_isBlocked_noFrameworkContainerCreated() {
        var runner = newRunner();
        var eid = UUID.randomUUID();
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "repo case", 0, null, null,
            snapshot("busybox:latest"));
        var ctx = new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), eid, UUID.randomUUID(),
            item, Duration.ofSeconds(30), 0, CancellationToken.never(), 0);

        var result = runner.execute(ctx);

        assertThat(result.status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(result.reason()).contains("allowlist");
        assertThat(docker.listContainersCmd().withShowAll(true)
            .withNameFilter(List.of("qualityops-run-" + eid + "-0-framework")).exec()).isEmpty();
    }
}
