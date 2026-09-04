package com.qualityops.worker.execution.adapter.out.container;

import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunResult;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunSpec;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.NetworkMode;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ResourceLimits;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.exception.ImageNotAllowlistedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-009 §1/§9 — create/start/wait/logs/rm against the local daemon, plus the
 *  digest-pin gate and the label-based orphan sweep. */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class DockerContainerRunnerIT extends AbstractDockerRunnerIT {

    private ContainerRunSpec spec(UUID executionId, List<String> command, Duration timeout) {
        return new ContainerRunSpec(executionId, 0, "framework", pinnedRef,
            List.of("sh", "-c"), command, "/tmp", Map.of("CI", "true"),
            workspaceRoot.resolve(executionId.toString()).resolve("0"),
            new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 128, 32L * 1024 * 1024,
                64L * 1024 * 1024, 1024, 2048),
            NetworkMode.NONE, timeout, Map.of("com.qualityops.run.id", "run-x"));
    }

    @Test
    void run_createsStartsWaitsAndStreamsLogs_thenCleanupRemovesTheContainer() {
        var runner = runner();
        var eid = UUID.randomUUID();
        var lines = new CopyOnWriteArrayList<String>();

        ContainerRunResult result;
        try {
            result = runner.run(spec(eid, List.of("echo hello-from-runner; id"), Duration.ofSeconds(20)),
                lines::add, CancellationToken.never());
        } finally {
            runner.cleanup(eid);
        }

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(String.join("\n", lines)).contains("hello-from-runner").contains("uid=12000");
        assertThat(docker.listContainersCmd().withShowAll(true)
            .withNameFilter(List.of("qualityops-run-" + eid + "-0-framework")).exec()).isEmpty();
    }

    @Test
    void run_commandExceedsTimeout_isKilledAndReportedAsTimedOut() {
        var runner = runner();
        var eid = UUID.randomUUID();
        try {
            var result = runner.run(spec(eid, List.of("sleep 3600"), Duration.ofSeconds(3)),
                l -> { }, CancellationToken.never());
            assertThat(result.timedOut()).isTrue();
            assertThat(result.exitCode()).isEqualTo(137);
        } finally {
            runner.cleanup(eid);
        }
    }

    @Test
    void run_nonAllowlistedImage_isRejectedWithoutCreatingAContainer() {
        var runner = runner();
        var eid = UUID.randomUUID();
        var bad = new ContainerRunSpec(eid, 0, "framework", "busybox:latest", List.of("sh", "-c"),
            List.of("true"), "/tmp", Map.of(), workspaceRoot.resolve(eid.toString()),
            new ResourceLimits(1L << 26, 1_000_000_000L, 64, 1L << 25, 1L << 26, 1024, 2048),
            NetworkMode.NONE, Duration.ofSeconds(5), Map.of());

        assertThatThrownBy(() -> runner.run(bad, l -> { }, CancellationToken.never()))
            .isInstanceOf(ImageNotAllowlistedException.class);
    }

    @Test
    void sweepOrphans_removesAManagedContainerWhoseExecutionIdIsNotLive() throws Exception {
        var runner = runner();
        var strayEid = UUID.randomUUID();
        // Create (do not start) a managed container that no live attempt owns.
        String name = "qualityops-run-" + strayEid + "-0-framework";
        try {
            docker.removeContainerCmd(name).withForce(true).exec();
        } catch (RuntimeException ignored) {
            // absent
        }
        var created = docker.createContainerCmd(pinnedRef)
            .withName(name)
            .withEntrypoint("sh", "-c").withCmd("sleep 1")
            .withLabels(Map.of(DockerContainerRunner.LABEL_MANAGED, "true",
                DockerContainerRunner.LABEL_EXECUTION, strayEid.toString()))
            .exec();
        try {
            int removed = runner.sweepOrphans(Set.of(UUID.randomUUID())); // strayEid not live

            assertThat(removed).isGreaterThanOrEqualTo(1);
            assertThat(docker.listContainersCmd().withShowAll(true)
                .withNameFilter(List.of(name)).exec()).isEmpty();
        } finally {
            try {
                docker.removeContainerCmd(created.getId()).withForce(true).exec();
            } catch (RuntimeException ignored) {
                // already swept
            }
        }
    }
}
