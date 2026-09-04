package com.qualityops.worker.execution.adapter.out.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §6 — PURE (no daemon): assert every {@code HostConfig} hardening
 *  field the runner builds for a sample {@code ContainerRunSpec}. */
class DockerContainerRunnerSpecTest {

    private static final long MEM = 1_073_741_824L;      // 1 GiB
    private static final long NANO_CPUS = 1_000_000_000L; // 1 CPU
    private static final long TMPFS = 268_435_456L;      // 256 MiB
    private static final long WORKSPACE = 2_147_483_648L; // 2 GiB

    private final DockerContainerRunner runner = new DockerContainerRunner(
        Mockito.mock(DockerClient.class), props(), new RepoExecMetrics(new SimpleMeterRegistry()));

    private static RepoExecWorkerProperties props() {
        return new RepoExecWorkerProperties(true,
            new Images("pw@sha256:x", "j@sha256:x", "py@sha256:x", "cy@sha256:x", "k6@sha256:x",
                "alpine/git:2.45@sha256:x"),
            true, Duration.ofMinutes(10), Duration.ofMinutes(30),
            com.qualityops.events.RepoResourceProfile.SMALL,
            new Docker("tcp://docker-proxy:2375", true),
            new Container(12000, 12000, 512, 256, 2048, Duration.ofSeconds(5), 4096, 8192),
            new Network("none", "qualityops-runner-egress"),
            Map.of("small", new Profile(1, 1024)),
            "/tmp/qo-ws", 20_971_520L, 4096, false, false,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(10), "QUALITYOPS_SECRET_");
    }

    private ContainerRunSpec spec(NetworkMode net) {
        return new ContainerRunSpec(UUID.fromString("11111111-1111-1111-1111-111111111111"), 0,
            "framework", "py@sha256:x", null, List.of("pytest", "--junitxml=report.xml"), "/workspace",
            Map.of("CI", "true"), Path.of("/var/run/qo/ws/exec/0"),
            new ResourceLimits(MEM, NANO_CPUS, 512, TMPFS, WORKSPACE, 4096, 8192),
            net, Duration.ofMinutes(5), Map.of("com.qualityops.run.id", "run-1"));
    }

    @Test
    void buildHostConfig_appliesEverySection6HardeningField() {
        HostConfig hc = runner.buildHostConfig(spec(NetworkMode.NONE), false);

        assertThat(hc.getCapDrop()).containsExactly(Capability.ALL);
        assertThat(hc.getCapAdd()).isNullOrEmpty();
        assertThat(hc.getReadonlyRootfs()).isTrue();
        assertThat(hc.getSecurityOpts()).contains("no-new-privileges:true");
        assertThat(hc.getMemory()).isEqualTo(MEM);
        assertThat(hc.getMemorySwap()).isEqualTo(MEM);
        assertThat(hc.getNanoCPUs()).isEqualTo(NANO_CPUS);
        assertThat(hc.getPidsLimit()).isEqualTo(512L);
        assertThat(hc.getInit()).isTrue();
        assertThat(hc.getNetworkMode()).isEqualTo("none");
        assertThat(hc.getTmpFs()).containsEntry("/tmp", "rw,noexec,nosuid,size=256m");
        assertThat(hc.getPrivileged()).isNull();
        assertThat(hc.getUlimits()).isNotEmpty();
    }

    @Test
    void buildHostConfig_bindsOnlyTheWorkspace_neverTheDockerSocket() {
        HostConfig hc = runner.buildHostConfig(spec(NetworkMode.NONE), false);

        Bind[] binds = hc.getBinds();
        assertThat(binds).hasSize(1);
        assertThat(binds[0].getVolume().getPath()).isEqualTo("/workspace");
        assertThat(binds[0].getPath()).doesNotContain("docker.sock").doesNotContain("docker_engine");
    }

    @Test
    void buildHostConfig_withoutStorageOpt_leavesItUnset() {
        assertThat(runner.buildHostConfig(spec(NetworkMode.NONE), false).getStorageOpt()).isNull();
    }

    @Test
    void buildHostConfig_withStorageOpt_setsSizeFromResourceLimits() {
        assertThat(runner.buildHostConfig(spec(NetworkMode.NONE), true).getStorageOpt())
            .containsEntry("size", "2048m");
    }

    @Test
    void buildHostConfig_egress_usesTheConfiguredEgressNetwork() {
        assertThat(runner.buildHostConfig(spec(NetworkMode.EGRESS), false).getNetworkMode())
            .isEqualTo("qualityops-runner-egress");
    }

    @Test
    void labels_alwaysCarryTheManagedAndProvenanceLabels() {
        Map<String, String> labels = runner.labels(spec(NetworkMode.NONE));

        assertThat(labels).containsEntry("com.qualityops.managed", "true")
            .containsEntry("com.qualityops.execution.id", "11111111-1111-1111-1111-111111111111")
            .containsEntry("com.qualityops.attempt", "0")
            .containsEntry("com.qualityops.phase", "framework")
            .containsEntry("com.qualityops.run.id", "run-1");
    }

    @Test
    void user_isTheNonRootRunnerUidGid() {
        assertThat(runner.user()).isEqualTo("12000:12000");
    }
}
