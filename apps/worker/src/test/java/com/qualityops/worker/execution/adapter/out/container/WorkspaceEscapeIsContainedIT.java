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
 * ADR-009 §6 — the container root filesystem is read-only
 * ({@code readonlyRootfs(true)}); only the bind-mounted {@code /workspace}
 * and the {@code noexec,nosuid} tmpfs {@code /tmp} are writable. A repo
 * command attempting to write outside those two paths (e.g. escaping via a
 * relative {@code ../}) fails at the filesystem layer regardless of what the
 * repo's own code tries to do — this is containment by the host config, not
 * by trusting the workload.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class WorkspaceEscapeIsContainedIT extends AbstractDockerRunnerIT {

    @Test
    void writeOutsideWorkspaceAndTmp_failsReadOnlyFilesystem() {
        var runner = runner();
        var eid = UUID.randomUUID();
        var lines = new CopyOnWriteArrayList<String>();
        var spec = new ContainerRunSpec(eid, 0, "framework", pinnedRef, List.of("sh", "-c"),
            List.of("echo pwned > /workspace/../etc/qo-escape-test 2>&1; echo rc=$?"), "/workspace",
            Map.of(), workspaceRoot.resolve(eid.toString()).resolve("0"),
            new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 64, 16L * 1024 * 1024, 32L * 1024 * 1024,
                1024, 2048),
            NetworkMode.NONE, Duration.ofSeconds(15), Map.of());

        try {
            var result = runner.run(spec, lines::add, CancellationToken.never());

            assertThat(result.timedOut()).isFalse();
            String out = String.join("\n", lines);
            assertThat(out).as("read-only rootfs ⇒ the write is refused, never rc=0")
                .contains("rc=").doesNotContain("rc=0");
        } finally {
            runner.cleanup(eid);
        }
    }

    @Test
    void writeInsideWorkspace_succeeds_provingOnlyTheMountIsWritable() {
        var runner = runner();
        var eid = UUID.randomUUID();
        var lines = new CopyOnWriteArrayList<String>();
        var spec = new ContainerRunSpec(eid, 0, "framework", pinnedRef, List.of("sh", "-c"),
            List.of("echo ok > /workspace/inside.txt && cat /workspace/inside.txt; echo rc=$?"), "/workspace",
            Map.of(), workspaceRoot.resolve(eid.toString()).resolve("0"),
            new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 64, 16L * 1024 * 1024, 32L * 1024 * 1024,
                1024, 2048),
            NetworkMode.NONE, Duration.ofSeconds(15), Map.of());

        try {
            var result = runner.run(spec, lines::add, CancellationToken.never());

            assertThat(result.exitCode()).isZero();
            assertThat(String.join("\n", lines)).contains("ok").contains("rc=0");
        } finally {
            runner.cleanup(eid);
        }
    }
}
