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
 * ADR-009 §6 — an {@code ISOLATED} ({@code RepoNetworkPolicy.NONE} →
 * {@link NetworkMode#NONE} → Docker network mode {@code "none"}) case has no
 * network at all: it cannot resolve or reach any host, including the
 * platform's own data services (Postgres/Redis/Kafka), by construction rather
 * than by an allowlist the repo could try to route around.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class RunnerCannotReachDataServicesIT extends AbstractDockerRunnerIT {

    @Test
    void isolatedNetworkMode_cannotResolveOrConnectToAnyHost() {
        var runner = runner();
        var eid = UUID.randomUUID();
        var lines = new CopyOnWriteArrayList<String>();
        var spec = new ContainerRunSpec(eid, 0, "framework", pinnedRef, List.of("sh", "-c"),
            List.of("wget -T 2 -O /dev/null http://postgres:5432 >/dev/null 2>&1; echo rc=$?"), "/tmp",
            Map.of(), workspaceRoot.resolve(eid.toString()).resolve("0"),
            new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 64, 16L * 1024 * 1024, 32L * 1024 * 1024,
                1024, 2048),
            NetworkMode.NONE, Duration.ofSeconds(15), Map.of());

        try {
            var result = runner.run(spec, lines::add, CancellationToken.never());

            assertThat(result.timedOut()).isFalse();
            String out = String.join("\n", lines);
            assertThat(out).as("no network ⇒ the request fails fast (DNS/connect error), never rc=0")
                .contains("rc=").doesNotContain("rc=0");
        } finally {
            runner.cleanup(eid);
        }
    }
}
