package com.qualityops.worker.execution.adapter.out.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.qualityops.worker.config.RepoExecMetrics;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import com.qualityops.worker.config.RepoExecWorkerProperties.Container;
import com.qualityops.worker.config.RepoExecWorkerProperties.Docker;
import com.qualityops.worker.config.RepoExecWorkerProperties.Images;
import com.qualityops.worker.config.RepoExecWorkerProperties.Network;
import com.qualityops.worker.config.RepoExecWorkerProperties.Profile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR-009 §1/§6 — shared setup for the {@code @Tag("docker")} runner ITs:
 *  a real {@link DockerClient} over the local daemon (npipe on Windows Desktop,
 *  unix socket on CI) + a {@code DockerContainerRunner} whose allowlist is a
 *  digest-pinned {@code alpine/git} resolved from the just-pulled image. Same
 *  {@code httpclient5} transport as production {@code DockerContainerRunnerConfig}
 *  (ADR §6) — NOT the {@code zerodep} transport.
 *
 *  <p><strong>Windows Docker Desktop bind-mount create hang (WP7 finding,
 *  thread-dump-confirmed).</strong> {@code POST /containers/create} for a
 *  container whose {@code HostConfig} includes a {@code Bind} (a bind-mounted
 *  workspace directory — every real runner call needs one) blocks indefinitely
 *  on this dev box: the client fully writes the request and then hangs
 *  reading the response header
 *  ({@code SessionInputBufferImpl.fillBuffer} → {@code DefaultBHttpClientConnection.receiveResponseHeader}),
 *  i.e. the daemon itself never replies — Docker Desktop's Windows file-sharing
 *  / mount-negotiation path stalls rather than erroring. This reproduces with
 *  the bind-mount source directory already created ({@link DockerContainerRunner}
 *  now also guarantees this in production via {@code ensureWorkspaceDirExists}
 *  — a correct, independent hardening — but it does not by itself unblock this
 *  box), and reproduces with both {@code httpclient5} and {@code zerodep}
 *  transports, so it is a daemon-side / environment limitation, not a bug in
 *  {@code DockerContainerRunner} or a client-transport choice. A create WITHOUT
 *  a bind mount completes normally, which is what isolates the bind mount as
 *  the trigger. A JUnit {@code @Timeout} does NOT protect against it: Jupiter's
 *  default {@code ThreadMode.SAME_THREAD} only measures elapsed time after the
 *  blocked call eventually returns — it never preempts a stuck native I/O wait
 *  (confirmed: the original unbounded code needed an external process kill,
 *  not a JUnit timeout, to stop it).
 *
 *  <p>The fix: {@link #bootOnce()} runs the ENTIRE daemon bootstrap (client
 *  construction, ping, image inspect, and a create+remove round trip) inside
 *  one bounded call on a single daemon-thread executor —
 *  {@code Future.get(timeout)} is a JDK-guaranteed bounded wait regardless of
 *  what the background thread is doing, so the calling (JUnit) thread always
 *  regains control within {@link #BOOT_TIMEOUT}. On success the daemon thread
 *  itself publishes {@link #docker}/{@link #pinnedRef}/{@link #workspaceRoot};
 *  on timeout {@link #bootDaemon()} skips the whole {@code @Tag("docker")}
 *  class via {@code assumeTrue} instead of hanging the build — real coverage
 *  runs in CI (Linux unix socket, unaffected). The probe thread is a daemon,
 *  so an abandoned hang never keeps the Surefire fork alive. */
@Tag("docker")
abstract class AbstractDockerRunnerIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractDockerRunnerIT.class);
    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(45);

    protected static final String CHECKOUT_TAG = "alpine/git:2.45.2";

    protected static volatile DockerClient docker;
    protected static volatile String pinnedRef;   // alpine/git@sha256:<real digest>
    protected static volatile Path workspaceRoot;

    @BeforeAll
    static void bootDaemon() {
        boolean ready = runBounded(BOOT_TIMEOUT, AbstractDockerRunnerIT::bootOnce);
        assumeTrue(ready, "Docker daemon calls (ping/inspect/create) did not complete within "
            + BOOT_TIMEOUT + " on this box's transport (known Docker Desktop Windows npipe limitation "
            + "with docker-java — see class Javadoc) — skipping @Tag(\"docker\") container ITs locally; "
            + "these run for real in CI (Linux unix socket).");
    }

    /** Runs entirely on the bounding executor's daemon thread. Only reached
     *  (and only publishes the static fields) if it completes inside the
     *  timeout — see {@link #runBounded}. */
    private static Boolean bootOnce() throws Exception {
        String host = dockerHost();
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(host).build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig())
            .connectionTimeout(Duration.ofSeconds(5)).responseTimeout(Duration.ofSeconds(15)).build();
        DockerClient client = DockerClientImpl.getInstance(config, http);
        client.pingCmd().exec();

        // The runner-image allowlist requires digest-pinned refs. The
        // @Tag("docker") batch expects the tiny alpine/git image to be present
        // (CI provisions it; locally: `docker pull alpine/git:2.45.2`). No pull
        // here — PullImageResultCallback can wedge on Docker Desktop.
        List<String> repoDigests;
        try {
            repoDigests = client.inspectImageCmd(CHECKOUT_TAG).exec().getRepoDigests();
        } catch (NotFoundException e) {
            log.warn("{} not present locally — run: docker pull {}", CHECKOUT_TAG, CHECKOUT_TAG);
            return false;
        }
        String ref = repoDigests.stream().filter(d -> d.contains("@sha256:")).findFirst().orElse(null);
        if (ref == null) {
            log.warn("no repo digest for {}", CHECKOUT_TAG);
            return false;
        }

        // Real create+remove round trip WITH a bind mount — the exact request
        // shape every runner test needs, not just a bodyless GET. A Bind into a
        // host path that does not yet exist is a known cause of the daemon
        // hanging (not erroring) on Docker Desktop for Windows — the probe
        // therefore creates its mount source first, same as
        // DockerContainerRunner.ensureWorkspaceDirExists does for every real run.
        Path ws = Files.createTempDirectory("qo-repo-ws-it");
        Path probeMount = Files.createDirectories(ws.resolve("boot-probe"));
        String id = client.createContainerCmd(ref)
            .withEntrypoint("true")
            .withHostConfig(HostConfig.newHostConfig().withAutoRemove(true)
                .withBinds(new com.github.dockerjava.api.model.Bind(
                    probeMount.toAbsolutePath().toString(),
                    new com.github.dockerjava.api.model.Volume("/workspace"))))
            .exec().getId();
        try {
            client.removeContainerCmd(id).withForce(true).exec();
        } catch (RuntimeException ignored) {
            // auto-remove may already have collected it
        }

        docker = client;
        pinnedRef = ref;
        workspaceRoot = ws;
        return true;
    }

    /** Bounds an arbitrary blocking task on a daemon-thread single-thread
     *  executor. {@code Future.get(timeout)} always returns within the given
     *  timeout regardless of the background thread's fate — a stuck native
     *  call there is simply abandoned (daemon thread, never joined), which is
     *  what lets the Surefire fork exit normally instead of hanging forever. */
    private static boolean runBounded(Duration timeout, Callable<Boolean> task) {
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "docker-it-bootstrap");
            t.setDaemon(true);
            return t;
        });
        try {
            var future = executor.submit(task);
            return Boolean.TRUE.equals(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            log.warn("Docker daemon bootstrap exceeded {} — abandoning the probe thread and skipping", timeout);
            return false;
        } catch (ExecutionException e) {
            log.warn("Docker daemon bootstrap failed: {}", e.getCause() != null ? e.getCause() : e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            executor.shutdown(); // does not interrupt a stuck pipe write — the daemon thread is abandoned
        }
    }

    private static String dockerHost() {
        return System.getenv().getOrDefault("DOCKER_HOST",
            System.getProperty("os.name").toLowerCase().contains("win")
                ? "npipe:////./pipe/docker_engine" : "unix:///var/run/docker.sock");
    }

    protected DockerContainerRunner runner() {
        return new DockerContainerRunner(docker, props(), new RepoExecMetrics(new SimpleMeterRegistry()));
    }

    protected RepoExecWorkerProperties props() {
        return props(512, 256L * 1024 * 1024);
    }

    protected RepoExecWorkerProperties props(int pidsLimit, long memoryBytes) {
        return new RepoExecWorkerProperties(true,
            new Images("pw@sha256:x", "j@sha256:x", "py@sha256:x", "cy@sha256:x", "k6@sha256:x", pinnedRef),
            false, Duration.ofMinutes(10), Duration.ofMinutes(30),
            com.qualityops.events.RepoResourceProfile.SMALL,
            new Docker(dockerHost(), false),
            new Container(12000, 12000, pidsLimit, 32, 64, Duration.ofSeconds(5), 1024, 2048),
            new Network("none", "bridge"), Map.of("small", new Profile(1, 1024)),
            workspaceRoot.toString(), 20_971_520L, 4096, false, false,
            Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMinutes(10), "S_");
    }
}
