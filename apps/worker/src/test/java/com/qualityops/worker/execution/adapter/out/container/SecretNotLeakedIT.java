package com.qualityops.worker.execution.adapter.out.container;

import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.execution.adapter.out.runner.Redactor;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunSpec;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.NetworkMode;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ResourceLimits;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.support.TestProps;
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

/**
 * ADR-009 §8 — a resolved {@code secretRef} plaintext injected as a framework
 * container env var, and echoed to stdout by the (adversarial) test command,
 * is masked in every console line the Worker keeps — the same
 * {@link Redactor#forExecution(Set)} literal-masking primitive
 * {@code RepositoryExecutionRunner} wires into its {@code LogSink} before a
 * line is ever appended to {@code consoleLines} / staged for upload. The raw
 * Docker daemon log (fetched independently, bypassing the Worker's sink) DOES
 * contain the plaintext — proving the masking is a Worker-side control on top
 * of, not a substitute for, never letting the plaintext leave the container
 * over the network.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class SecretNotLeakedIT extends AbstractDockerRunnerIT {

    private static final String SECRET_PLAINTEXT = "db-password-9f8e7d6c";

    @Test
    void secretEchoedByContainer_isMaskedInEveryWorkerCapturedLine() throws InterruptedException {
        var runner = runner();
        var eid = UUID.randomUUID();
        var redaction = new Redactor(TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), null,
            new Redaction(List.of(), List.of()), false)).forExecution(Set.of(SECRET_PLAINTEXT));
        var maskedLines = new CopyOnWriteArrayList<String>();
        var spec = new ContainerRunSpec(eid, 0, "framework", pinnedRef, List.of("sh", "-c"),
            List.of("echo connecting with secret=$DB_PASSWORD"), "/tmp",
            Map.of("DB_PASSWORD", SECRET_PLAINTEXT), workspaceRoot.resolve(eid.toString()).resolve("0"),
            new ResourceLimits(64L * 1024 * 1024, 1_000_000_000L, 64, 16L * 1024 * 1024, 32L * 1024 * 1024,
                1024, 2048),
            NetworkMode.NONE, Duration.ofSeconds(15), Map.of());

        String containerId;
        try {
            runner.run(spec, line -> maskedLines.add(redaction.line(line)), CancellationToken.never());
            containerId = firstManagedContainerId(eid);

            String maskedOutput = String.join("\n", maskedLines);
            assertThat(maskedOutput).as("the Worker's own captured/masked view never carries the plaintext")
                .doesNotContain(SECRET_PLAINTEXT).contains("REDACTED");

            String rawDaemonLog = rawLogOf(containerId);
            assertThat(rawDaemonLog).as("the container really did emit the plaintext (sanity check the test "
                + "is exercising a genuine leak, not a no-op)").contains(SECRET_PLAINTEXT);
        } finally {
            runner.cleanup(eid);
        }
    }

    private static String firstManagedContainerId(UUID executionId) {
        var matches = docker.listContainersCmd().withShowAll(true)
            .withNameFilter(List.of("qualityops-run-" + executionId + "-0-framework")).exec();
        assertThat(matches).hasSize(1);
        return matches.get(0).getId();
    }

    private static String rawLogOf(String containerId) throws InterruptedException {
        var buf = new StringBuilder();
        docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true).withTailAll()
            .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<
                com.github.dockerjava.api.model.Frame>() {
                @Override
                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                    buf.append(new String(frame.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }).awaitCompletion(10, TimeUnit.SECONDS);
        return buf.toString();
    }
}
