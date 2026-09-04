package com.qualityops.worker.execution.adapter.out.container;

import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunSpec;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.NetworkMode;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ResourceLimits;
import com.qualityops.worker.execution.domain.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-009 §6 / PHASE-2-PLAN §2F "resource limits enforced" — proves the
 * {@code HostConfig} limits {@link DockerContainerRunner} sets are honoured by
 * the real daemon, not just present in the request (that half is
 * {@code DockerContainerRunnerSpecTest}, pure/no daemon).
 *
 * <p><strong>Not covered here — the workspace-quota watchdog</strong>
 * ({@code >max-workspace-mb} writer): per {@link DockerContainerRunner}'s class
 * Javadoc, {@code withStorageOpt} is best-effort and confirmed unsupported on
 * this dev box's overlay2 driver; the disk bound of record is the Worker-side
 * {@code du} watchdog that lives in {@code RepositoryExecutionRunner} (WP8),
 * not in this port implementation. That case is exercised in WP8's security
 * IT suite instead.
 *
 * <p>The pids-limit assertion reads the container's own {@code pids.max}
 * cgroup file rather than counting fork successes/failures from a shell loop —
 * fork-failure reporting is shell/busybox-version dependent and not reliably
 * assertable from outside the container; reading the cgroup file the kernel
 * actually enforces against is deterministic and portable.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ResourceLimitsEnforcedIT extends AbstractDockerRunnerIT {

    private ContainerRunSpec spec(UUID eid, List<String> command, ResourceLimits limits, Duration timeout) {
        return new ContainerRunSpec(eid, 0, "framework", pinnedRef,
            List.of("sh", "-c"), command, "/tmp", Map.of("CI", "true"),
            workspaceRoot.resolve(eid.toString()).resolve("0"), limits,
            NetworkMode.NONE, timeout, Map.of("com.qualityops.run.id", "run-x"));
    }

    @Test
    void run_memoryExceedsLimit_isOomKilledAndCleanedUp() {
        var runner = runner();
        var eid = UUID.randomUUID();
        // Grows an in-shell string without bound — no external tool needed
        // (busybox ash string concatenation), so it works regardless of which
        // utilities the pinned checkout image happens to ship.
        var limits = new ResourceLimits(16L * 1024 * 1024, 1_000_000_000L, 64,
            16L * 1024 * 1024, 64L * 1024 * 1024, 1024, 2048);
        var cmd = List.of("a=; while true; do a=\"$a XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\"; done");
        var runSpec = spec(eid, cmd, limits, Duration.ofSeconds(30));

        try {
            var result = runner.run(runSpec, l -> { }, CancellationToken.never());

            assertThat(result.exitCode()).isEqualTo(137);
            assertThat(result.timedOut()).isFalse(); // killed by the kernel OOM-killer, not our timeout path
            boolean oomKilled = Boolean.TRUE.equals(
                docker.inspectContainerCmd(runSpec.containerName()).exec().getState().getOOMKilled());
            assertThat(oomKilled).as("container state.OOMKilled").isTrue();
        } finally {
            runner.cleanup(eid);
        }
        assertThat(docker.listContainersCmd().withShowAll(true)
            .withNameFilter(List.of("qualityops-run-" + eid + "-0-framework")).exec()).isEmpty();
    }

    @Test
    void run_pidsLimitConfigured_isEnforcedByTheKernelCgroup() {
        var runner = runner();
        var eid = UUID.randomUUID();
        var limits = new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 7,
            16L * 1024 * 1024, 64L * 1024 * 1024, 1024, 2048);
        // cgroup v2 path first, v1 fallback — whichever the daemon uses.
        var cmd = List.of("cat /sys/fs/cgroup/pids.max 2>/dev/null || cat /sys/fs/cgroup/pids/pids.max");
        var lines = new CopyOnWriteArrayList<String>();

        try {
            var result = runner.run(spec(eid, cmd, limits, Duration.ofSeconds(20)),
                lines::add, CancellationToken.never());

            assertThat(result.exitCode()).isZero();
            assertThat(String.join("\n", lines).trim()).isEqualTo("7");
        } finally {
            runner.cleanup(eid);
        }
    }

    @Test
    void run_readOnlyRootfs_rejectsWritesOutsideWorkspaceAndTmp() {
        var runner = runner();
        var eid = UUID.randomUUID();
        var limits = new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 64,
            16L * 1024 * 1024, 64L * 1024 * 1024, 1024, 2048);
        var cmd = List.of("echo x > /etc/qualityops-write-test 2>&1; echo EXIT=$?");
        var lines = new CopyOnWriteArrayList<String>();

        try {
            var result = runner.run(spec(eid, cmd, limits, Duration.ofSeconds(15)),
                lines::add, CancellationToken.never());

            assertThat(result.exitCode()).isZero(); // the outer `echo EXIT=$?` always succeeds
            String output = String.join("\n", lines);
            assertThat(output).as("write to the read-only rootfs must fail").contains("EXIT=1");
            assertThat(output).doesNotContain("EXIT=0");
        } finally {
            runner.cleanup(eid);
        }
    }
}
