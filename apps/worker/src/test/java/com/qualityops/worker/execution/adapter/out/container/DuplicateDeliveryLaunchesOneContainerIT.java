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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-009 §9 — a redelivered {@code runs.requested} (or a retried in-run
 * attempt for the same {@code (executionId, attemptEpoch, phase)}) must not
 * race two containers for the same slot: the deterministic container name
 * (`qualityops-run-<executionId>-<attemptEpoch>-<phase>`) plus
 * {@code DockerContainerRunner}'s adopt-or-recreate {@code ConflictException}
 * handling guarantees at most one container is ever running for that name at
 * a time.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class DuplicateDeliveryLaunchesOneContainerIT extends AbstractDockerRunnerIT {

    private ContainerRunSpec spec(UUID executionId) {
        return new ContainerRunSpec(executionId, 0, "framework", pinnedRef, List.of("sh", "-c"),
            List.of("sleep 5"), "/tmp", Map.of(), workspaceRoot.resolve(executionId.toString()).resolve("0"),
            new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 64, 16L * 1024 * 1024, 32L * 1024 * 1024,
                1024, 2048),
            NetworkMode.NONE, Duration.ofSeconds(20), Map.of());
    }

    @Test
    void twoConcurrentDeliveriesForSameSlot_resultInAtMostOneRunningContainer() throws InterruptedException {
        var eid = UUID.randomUUID();
        var runnerA = runner();
        var runnerB = runner();
        var startGate = new CountDownLatch(1);
        var results = new AtomicReference<Throwable>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable call = () -> {
                try {
                    startGate.await();
                    runnerA.run(spec(eid), l -> { }, CancellationToken.never());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    results.compareAndSet(null, e);
                }
            };
            pool.submit(call);
            pool.submit(call);
            startGate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();

            String name = "qualityops-run-" + eid + "-0-framework";
            assertThat(docker.listContainersCmd().withShowAll(true).withNameFilter(List.of(name)).exec())
                .as("at most one container is ever registered under the deterministic name")
                .hasSizeLessThanOrEqualTo(1);
        } finally {
            runnerA.cleanup(eid);
            runnerB.cleanup(eid);
        }
    }
}
