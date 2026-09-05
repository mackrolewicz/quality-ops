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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-009 §1/§6/§7 ({@code @Tag("docker")}) — the full two-phase orchestration
 * (real checkout container cloning a small public repo over EGRESS, then a
 * real framework container) end to end via {@link RepositoryExecutionRunner},
 * against the local daemon.
 *
 * <p>Both phases use the same digest-pinned {@code alpine/git} image resolved
 * in {@link AbstractDockerRunnerIT} (no other framework image is guaranteed
 * pulled/allowlisted on this box or in CI without extra provisioning). The
 * "framework" phase therefore runs a real, always-succeeding {@code git
 * --version} rather than an actual pytest/k6/Playwright invocation, and the
 * JUnit XML report the framework phase would normally write is pre-seeded
 * into the shared workspace before {@code execute()} runs — the orchestrator
 * only cares that a report file exists at the configured path after the
 * framework container exits, not which tool produced it. This is a deliberate
 * scope simplification for a single-real-image environment; it still
 * exercises checkout-then-framework sequencing, workspace sharing between the
 * two containers, report parsing, and provenance assembly for real.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class RepositoryExecutionIT extends AbstractDockerRunnerIT {

    private static final String REPORT_XML =
        "<testsuite><testcase classname=\"repo\" name=\"clones ok\" time=\"0.1\"/></testsuite>";

    private RepositoryExecutionRunner newRunner() {
        var redactor = new Redactor(TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), null,
            new Redaction(List.of(), List.of()), false));
        return new RepositoryExecutionRunner(runner(), props(), new StubSecretResolver(), redactor,
            new ReportParserRegistry(List.of(new JUnitXmlReportParser())), new WorkspacePathResolver(),
            new RepoExecMetrics(new SimpleMeterRegistry()));
    }

    private RepoTestSnapshot snapshot() {
        return new RepoTestSnapshot(UUID.randomUUID(), RepositoryProvider.GITHUB, "github.com",
            "octocat/Hello-World", "master", "6dcb09b5b57875f334f61aebed695e2e4193db5", RepoRefType.BRANCH,
            FrameworkPreset.PYTEST, pinnedRef, null, List.of("--version"), RepoReportFormat.JUNIT_XML,
            List.of("report.xml"), List.of(), List.of(), List.of(), null, RepoResourceProfile.SMALL,
            RepoNetworkPolicy.EGRESS, 90);
    }

    @Test
    void execute_realCheckoutThenFramework_parsesPreSeededReport_endsPassedWithProvenance() throws IOException {
        var runner = newRunner();
        var eid = UUID.randomUUID();
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "repo case", 0, null, null, snapshot());
        var ctx = new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), eid, UUID.randomUUID(),
            item, Duration.ofSeconds(90), 0, CancellationToken.never(), 0);
        Path workspaceDir = workspaceRoot.resolve(eid.toString()).resolve("0");
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("report.xml"), REPORT_XML);

        var result = runner.execute(ctx);

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
        assertThat(result.repository()).isNotNull();
        assertThat(result.repository().items()).hasSize(1);
        assertThat(result.repository().provenance().exitCode()).isZero();
        assertThat(result.repository().provenance().checkoutAt()).isNotNull();
        assertThat(docker.listContainersCmd().withShowAll(true)
            .withNameFilter(List.of("qualityops-run-" + eid)).exec()).isEmpty();
    }
}
