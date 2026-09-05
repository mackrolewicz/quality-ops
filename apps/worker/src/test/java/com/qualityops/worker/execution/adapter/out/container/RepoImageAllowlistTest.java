package com.qualityops.worker.execution.adapter.out.container;

import com.github.dockerjava.api.DockerClient;
import com.qualityops.worker.config.RepoExecMetrics;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import com.qualityops.worker.config.RepoExecWorkerProperties.Container;
import com.qualityops.worker.config.RepoExecWorkerProperties.Docker;
import com.qualityops.worker.config.RepoExecWorkerProperties.Images;
import com.qualityops.worker.config.RepoExecWorkerProperties.Network;
import com.qualityops.worker.config.RepoExecWorkerProperties.Profile;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ContainerRunSpec;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.NetworkMode;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort.ResourceLimits;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.exception.DigestMismatchException;
import com.qualityops.worker.execution.exception.ImageNotAllowlistedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/** ADR-009 §5 — image allowlist + digest-pin enforcement, no daemon. */
class RepoImageAllowlistTest {

    private static final String PYTEST = "python:3.12-slim@sha256:"
        + "1111111111111111111111111111111111111111111111111111111111111111";

    private final DockerClient docker = Mockito.mock(DockerClient.class);
    private final DockerContainerRunner runner = new DockerContainerRunner(docker, props(),
        new RepoExecMetrics(new SimpleMeterRegistry()));

    private static RepoExecWorkerProperties props() {
        return new RepoExecWorkerProperties(true,
            new Images("pw@sha256:x", "j@sha256:x", PYTEST, "cy@sha256:x", "k6@sha256:x",
                "alpine/git@sha256:x"),
            true, Duration.ofMinutes(10), Duration.ofMinutes(30),
            com.qualityops.events.RepoResourceProfile.SMALL,
            new Docker("tcp://docker-proxy:2375", true),
            new Container(12000, 12000, 512, 256, 2048, Duration.ofSeconds(5), 4096, 8192),
            new Network("none", "egress"), Map.of("small", new Profile(1, 1024)),
            "/tmp/qo-ws", 20_971_520L, 4096, false, false,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(10), "S_");
    }

    private ContainerRunSpec spec(String imageRef) {
        return new ContainerRunSpec(UUID.randomUUID(), 0, "framework", imageRef, null,
            List.of("pytest"), "/workspace", Map.of(), Path.of("/tmp/ws"),
            new ResourceLimits(1L << 30, 1_000_000_000L, 512, 1L << 28, 1L << 31, 4096, 8192),
            NetworkMode.NONE, Duration.ofMinutes(5), Map.of());
    }

    @Test
    void run_imageNotOnAllowlist_throwsImageNotAllowlisted_withoutTouchingTheDaemon() {
        assertThatThrownBy(() -> runner.run(spec("evil/image:latest"), line -> { }, CancellationToken.never()))
            .isInstanceOf(ImageNotAllowlistedException.class);

        verifyNoInteractions(docker);
    }

    @Test
    void assertDigestMatches_repoDigestDiffersFromPin_throwsDigestMismatch() {
        assertThatThrownBy(() -> DockerContainerRunner.assertDigestMatches(
            PYTEST, "sha256:1111111111111111111111111111111111111111111111111111111111111111",
            List.of("python:3.12-slim@sha256:9999999999999999999999999999999999999999999999999999999999999999")))
            .isInstanceOf(DigestMismatchException.class);
    }

    @Test
    void assertDigestMatches_repoDigestMatchesThePin_passes() {
        assertThatCode(() -> DockerContainerRunner.assertDigestMatches(
            PYTEST, "sha256:1111111111111111111111111111111111111111111111111111111111111111",
            List.of("python:3.12-slim@sha256:1111111111111111111111111111111111111111111111111111111111111111")))
            .doesNotThrowAnyException();
    }

    @Test
    void assertDigestMatches_noRepoDigests_throwsDigestMismatch() {
        assertThatThrownBy(() -> DockerContainerRunner.assertDigestMatches(PYTEST, "sha256:abc", null))
            .isInstanceOf(DigestMismatchException.class);
    }
}
