package com.qualityops.worker.execution.adapter.out.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
import com.github.dockerjava.api.model.Volume;
import com.qualityops.worker.config.RepoExecMetrics;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import com.qualityops.worker.execution.application.port.out.ContainerRunnerPort;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.exception.ContainerRunException;
import com.qualityops.worker.execution.exception.DigestMismatchException;
import com.qualityops.worker.execution.exception.ImageNotAllowlistedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ADR-009 §1/§5/§6/§9 — the {@link DockerClient}-backed {@link ContainerRunnerPort}.
 * Runs ONE hardened sibling container per call; never runs repo code in-JVM.
 * Refuses any image not byte-equal to a {@code qualityops.repo-exec.images.*}
 * value, verifies the pulled digest against the pin, uses inspect-not-pull at
 * create, applies every §6 {@code HostConfig} limit, and tracks managed
 * containers for {@link #cleanup} / {@link #sweepOrphans}.
 *
 * <p><strong>Disk quota:</strong> {@code withStorageOpt({"size": ...})} is
 * best-effort — Docker Desktop / overlay2 without xfs-pquota rejects or ignores
 * it (confirmed on the dev box). The Worker-side {@code du} watchdog in
 * {@code RepositoryExecutionRunner} (WP8) is the disk bound of record.
 *
 * <p><strong>Bind-mount source must exist first.</strong> {@link #run} calls
 * {@link #ensureWorkspaceDirExists} before every create — a {@code Bind} into a
 * host path that does not yet exist is a known trigger for the daemon to stall
 * (not error) on Docker Desktop for Windows (see {@code AbstractDockerRunnerIT}'s
 * class Javadoc for the thread-dump-confirmed finding). This call is always
 * correct defensively even where it is not the sole cause of a given hang.
 */
@Component
@ConditionalOnProperty(name = "qualityops.repo-exec.enabled", havingValue = "true", matchIfMissing = true)
public class DockerContainerRunner implements ContainerRunnerPort {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerRunner.class);

    static final String LABEL_MANAGED = "com.qualityops.managed";
    static final String LABEL_EXECUTION = "com.qualityops.execution.id";
    static final String LABEL_RUN = "com.qualityops.run.id";
    static final String LABEL_ATTEMPT = "com.qualityops.attempt";
    static final String LABEL_PHASE = "com.qualityops.phase";
    private static final String WORKSPACE_MOUNT = "/workspace";
    private static final Duration CANCEL_KILL_GRACE = Duration.ofSeconds(5);

    private final DockerClient docker;
    private final RepoExecWorkerProperties props;
    private final RepoExecMetrics metrics;
    private final List<String> allowlist;
    /** executionId -> container ids this JVM created for it. */
    private final Map<UUID, Set<String>> managed = new ConcurrentHashMap<>();
    private volatile boolean storageOptSupported = true;

    public DockerContainerRunner(DockerClient docker, RepoExecWorkerProperties props,
                                 RepoExecMetrics metrics) {
        this.docker = docker;
        this.props = props;
        this.metrics = metrics;
        this.allowlist = props.images() == null ? List.of() : props.images().all();
    }

    @Override
    public ContainerRunResult run(ContainerRunSpec spec, LogSink logs, CancellationToken cancel)
            throws ContainerRunException {
        requireAllowlisted(spec.imageRef());
        verifyDigest(spec.imageRef());
        ensureWorkspaceDirExists(spec.workspaceHostDir());

        String name = spec.containerName();
        String id = createOrAdopt(spec, name);
        managed.computeIfAbsent(spec.executionId(), k -> ConcurrentHashMap.newKeySet()).add(id);

        Instant startedAt = Instant.now();
        docker.startContainerCmd(id).exec();
        var watcher = startCancelWatcher(id, cancel);
        var workspaceWatchdog = startWorkspaceWatchdog(id, name, spec.workspaceHostDir(),
            spec.limits().workspaceBytes(), props.container() == null ? null : props.container().workspaceWatchdog());

        boolean timedOut = false;
        try {
            long timeoutSecs = Math.max(1, spec.timeout().toSeconds());
            docker.waitContainerCmd(id).exec(new WaitContainerResultCallback())
                .awaitStatusCode(timeoutSecs, TimeUnit.SECONDS);
        } catch (DockerClientException e) {
            timedOut = true;
            log.warn("container {} exceeded {} — SIGKILL", name, spec.timeout());
            metrics.containerKill("timeout");
            killQuietly(id, "SIGKILL");
        } finally {
            watcher.interrupt();
            workspaceWatchdog.interrupt();
        }
        // One-shot log fetch after the container has stopped — robust across
        // daemon transports (a follow-stream can wedge the httpclient5 connection
        // on Windows npipe). The LogSink still receives one line at a time.
        drainLogs(id, logs);

        boolean cancelled = cancel.isCancelled();
        // A SIGKILLed container's inspect can still show a running state / exit 0
        // for a moment — on a timeout the outcome is deterministically 137.
        int exitCode = timedOut ? 137 : inspectExitCode(id).orElse(cancelled ? 137 : -1);
        Instant finishedAt = Instant.now();
        log.info("container {} finished exit={} timedOut={} cancelled={} ({}ms)",
            name, exitCode, timedOut, cancelled, Duration.between(startedAt, finishedAt).toMillis());
        return new ContainerRunResult(exitCode, timedOut, cancelled, startedAt, finishedAt);
    }

    @Override
    public void cleanup(UUID executionId) {
        Set<String> ids = managed.remove(executionId);
        if (ids != null) {
            ids.forEach(this::forceRemoveQuietly);
        }
        // Belt-and-braces: any container carrying this executionId label (adopted / renamed).
        try {
            listManaged().stream()
                .filter(c -> executionId.toString().equals(label(c, LABEL_EXECUTION)))
                .forEach(c -> forceRemoveQuietly(c.getId()));
        } catch (RuntimeException e) {
            log.debug("cleanup label scan for {} failed: {}", executionId, e.toString());
        }
        deleteWorkspaceDir(executionId);
    }

    @Override
    public int sweepOrphans(Set<UUID> liveExecutionIds) {
        List<Container> managedContainers;
        try {
            managedContainers = listManaged();
        } catch (RuntimeException e) {
            log.warn("orphan sweep: could not list managed containers ({})", e.toString());
            return 0;
        }
        long budgetSeconds = staleAfter().toSeconds();
        long nowEpoch = Instant.now().getEpochSecond();
        int removed = 0;
        for (Container c : managedContainers) {
            UUID eid = parseUuid(label(c, LABEL_EXECUTION));
            boolean tooOld = c.getCreated() != null && (nowEpoch - c.getCreated()) > budgetSeconds;
            boolean notLive = eid == null || !liveExecutionIds.contains(eid);
            if (notLive || tooOld) {
                forceRemoveQuietly(c.getId());
                if (eid != null) {
                    managed.getOrDefault(eid, Set.of()).remove(c.getId());
                    deleteWorkspaceDir(eid);
                }
                removed++;
            }
        }
        if (removed > 0) {
            metrics.orphansSwept().increment(removed);
            metrics.containerKill("sweep");
            log.info("orphan sweep removed {} managed container(s)", removed);
        }
        return removed;
    }

    /** Package-private — {@code RepoContainerSweeper} folds these against
     *  {@code worker.execution_attempt} to compute {@code liveExecutionIds}. */
    Set<UUID> managedExecutionIds() {
        try {
            return listManaged().stream()
                .map(c -> parseUuid(label(c, LABEL_EXECUTION)))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            log.warn("managedExecutionIds scan failed: {}", e.toString());
            return Set.of();
        }
    }

    // --- create / adopt ---

    private String createOrAdopt(ContainerRunSpec spec, String name) {
        try {
            return newContainer(spec, name);
        } catch (ConflictException clash) {
            log.warn("container name {} already exists — adopt-or-recreate", name);
            var existing = inspectByNameQuietly(name);
            if (existing != null && Boolean.TRUE.equals(existing.getState().getRunning())) {
                log.info("adopting already-running container {}", name);
                return existing.getId();
            }
            if (existing != null) {
                forceRemoveQuietly(existing.getId());
            }
            return newContainer(spec, name);
        }
    }

    private String newContainer(ContainerRunSpec spec, String name) {
        try (CreateContainerCmd cmd = docker.createContainerCmd(spec.imageRef())) {
            configure(cmd, spec, storageOptSupported);
            return cmd.exec().getId();
        } catch (NotFoundException e) {
            throw new ContainerRunException("runner image not present locally (pre-pull failed): "
                + spec.imageRef(), e);
        } catch (DockerException e) {
            if (storageOptSupported && mentionsStorageOpt(e)) {
                log.warn("daemon rejected withStorageOpt (overlay2 without pquota) — disabling; the du "
                    + "watchdog is the disk bound of record. ({})", e.getMessage());
                storageOptSupported = false;
                try (CreateContainerCmd retry = docker.createContainerCmd(spec.imageRef())) {
                    configure(retry, spec, false);
                    return retry.exec().getId();
                }
            }
            throw new ContainerRunException("createContainer failed for " + name, e);
        }
    }

    void configure(CreateContainerCmd cmd, ContainerRunSpec spec, boolean withStorageOpt) {
        cmd.withName(spec.containerName())
            .withHostConfig(buildHostConfig(spec, withStorageOpt))
            .withUser(user())
            .withLabels(labels(spec))
            .withEnv(envList(spec))
            .withWorkingDir(spec.workingDir() == null || spec.workingDir().isBlank()
                ? WORKSPACE_MOUNT : spec.workingDir());
        if (spec.entrypoint() != null && !spec.entrypoint().isEmpty()) {
            cmd.withEntrypoint(spec.entrypoint());
        }
        if (spec.command() != null && !spec.command().isEmpty()) {
            cmd.withCmd(spec.command());
        }
    }

    HostConfig buildHostConfig(ContainerRunSpec spec, boolean withStorageOpt) {
        ResourceLimits lim = spec.limits();
        var binds = new java.util.ArrayList<Bind>();
        binds.add(new Bind(spec.workspaceHostDir().toAbsolutePath().toString(), new Volume(WORKSPACE_MOUNT)));
        if (spec.secretBinds() != null) {
            spec.secretBinds().forEach((hostPath, containerPath) ->
                binds.add(new Bind(hostPath.toAbsolutePath().toString(), new Volume(containerPath), AccessMode.ro)));
        }
        HostConfig hc = HostConfig.newHostConfig()
            .withCapDrop(Capability.ALL)
            .withReadonlyRootfs(true)
            // Docker applies its own default seccomp profile with no explicit
            // --security-opt seccomp= flag at all; "seccomp=default" is NOT a
            // valid value (only a JSON profile path or "unconfined" are) and
            // would fail container-create on a daemon that validates it strictly.
            .withSecurityOpts(List.of("no-new-privileges:true"))
            .withMemory(lim.memoryBytes())
            .withMemorySwap(lim.memoryBytes()) // == Memory ⇒ swap disabled
            .withNanoCPUs(lim.nanoCpus())
            .withPidsLimit(lim.pidsLimit() <= 0 ? null : (long) lim.pidsLimit())
            .withInit(true)
            .withNetworkMode(networkMode(spec.network()))
            .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=" + Math.max(1, lim.tmpfsBytes() >> 20) + "m"))
            .withUlimits(new Ulimit[]{new Ulimit("nofile", lim.nofileSoft(), lim.nofileHard())})
            .withBinds(binds);
        if (withStorageOpt && lim.workspaceBytes() > 0) {
            hc.withStorageOpt(Map.of("size", (Math.max(1, lim.workspaceBytes() >> 20)) + "m"));
        }
        return hc;
    }

    private String networkMode(NetworkMode mode) {
        if (mode == NetworkMode.EGRESS) {
            var net = props.network();
            return net != null && net.egressNetwork() != null && !net.egressNetwork().isBlank()
                ? net.egressNetwork() : "bridge";
        }
        var net = props.network();
        return net != null && net.isolatedMode() != null && !net.isolatedMode().isBlank()
            ? net.isolatedMode() : "none";
    }

    String user() {
        var c = props.container();
        int uid = c != null ? c.runnerUid() : 12000;
        int gid = c != null ? c.runnerGid() : 12000;
        return uid + ":" + gid;
    }

    Map<String, String> labels(ContainerRunSpec spec) {
        var m = new LinkedHashMap<String, String>();
        if (spec.labels() != null) {
            m.putAll(spec.labels());
        }
        m.put(LABEL_MANAGED, "true");
        m.put(LABEL_EXECUTION, spec.executionId().toString());
        m.put(LABEL_ATTEMPT, Integer.toString(spec.attemptEpoch()));
        m.put(LABEL_PHASE, spec.phase());
        return m;
    }

    private static List<String> envList(ContainerRunSpec spec) {
        if (spec.env() == null) {
            return List.of();
        }
        return spec.env().entrySet().stream()
            .map(e -> e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()))
            .toList();
    }

    // --- digest / allowlist ---

    private void requireAllowlisted(String imageRef) {
        if (!allowlist.contains(imageRef)) {
            metrics.blocked("image_not_allowlisted");
            throw new ImageNotAllowlistedException(imageRef);
        }
    }

    private void verifyDigest(String imageRef) {
        int at = imageRef.indexOf('@');
        if (at < 0) {
            // config value is not digest-pinned — treat as a mismatch (ADR §5).
            metrics.blocked("digest_mismatch");
            throw new DigestMismatchException(imageRef, "<digest-pinned ref required>", "<none>");
        }
        String pinnedDigest = imageRef.substring(at + 1); // sha256:...
        List<String> repoDigests;
        try {
            repoDigests = docker.inspectImageCmd(imageRef).exec().getRepoDigests();
        } catch (NotFoundException e) {
            throw new ContainerRunException("runner image not present locally (pre-pull failed): "
                + imageRef, e);
        }
        assertDigestMatches(imageRef, pinnedDigest, repoDigests);
    }

    static void assertDigestMatches(String imageRef, String pinnedDigest, List<String> repoDigests) {
        boolean ok = repoDigests != null && repoDigests.stream()
            .anyMatch(d -> d != null && d.endsWith("@" + pinnedDigest));
        if (!ok) {
            throw new DigestMismatchException(imageRef, pinnedDigest,
                repoDigests == null ? "<none>" : String.join(",", repoDigests));
        }
    }

    private static boolean mentionsStorageOpt(DockerException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return m.contains("storage-opt") || m.contains("storage opt") || m.contains("pquota")
            || (m.contains("size") && m.contains("not supported"));
    }

    // --- log streaming ---

    private void drainLogs(String id, LogSink sink) {
        var buffer = new StringBuilder();
        var cb = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                buffer.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                int nl;
                while ((nl = buffer.indexOf("\n")) >= 0) {
                    sink.accept(buffer.substring(0, nl));
                    buffer.delete(0, nl + 1);
                }
            }

            @Override
            public void onComplete() {
                if (buffer.length() > 0) {
                    sink.accept(buffer.toString());
                }
            }
        };
        try {
            docker.logContainerCmd(id).withStdOut(true).withStdErr(true).withTailAll()
                .withFollowStream(false).exec(cb).awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("log drain for {} failed: {}", id, e.toString());
        } finally {
            try {
                cb.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // --- cancel watcher ---

    private Thread startCancelWatcher(String id, CancellationToken cancel) {
        Duration poll = props.cancelPoll() == null ? Duration.ofSeconds(2) : props.cancelPoll();
        Duration grace = props.hardKillGrace() == null ? CANCEL_KILL_GRACE : props.hardKillGrace();
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (cancel.isCancelled()) {
                        log.info("cancellation requested — SIGTERM {} then SIGKILL after {}", id, grace);
                        metrics.containerKill("cancel");
                        killQuietly(id, "SIGTERM");
                        Thread.sleep(grace.toMillis());
                        killQuietly(id, "SIGKILL");
                        return;
                    }
                    Thread.sleep(poll.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "repo-cancel-watch-" + id.substring(0, Math.min(12, id.length())));
        t.setDaemon(true);
        t.start();
        return t;
    }

    // --- workspace watchdog ---

    /** ADR-009 §6/§9 Risks — {@code withStorageOpt} is best-effort (overlay2
     *  without xfs-pquota rejects or ignores it); this watchdog is the disk
     *  bound of record when it is unsupported. Polls the bind-mounted
     *  workspace's total size and SIGKILLs the container the first time it
     *  exceeds {@code workspaceBytes}. Best-effort: a transient stat failure
     *  mid-write (a file being written/removed concurrently) is swallowed, not
     *  fatal — the next poll retries. */
    private Thread startWorkspaceWatchdog(String id, String name, Path workspaceHostDir, long workspaceBytes,
                                          Duration configuredInterval) {
        Duration interval = configuredInterval == null || configuredInterval.isZero() || configuredInterval.isNegative()
            ? Duration.ofSeconds(5) : configuredInterval;
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(interval.toMillis());
                    long size = workspaceSizeBytes(workspaceHostDir);
                    if (exceedsQuota(size, workspaceBytes)) {
                        log.warn("container {} workspace {} exceeded {} bytes ({} used) — SIGKILL",
                            name, workspaceHostDir, workspaceBytes, size);
                        metrics.containerKill("workspace_quota");
                        killQuietly(id, "SIGKILL");
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "repo-workspace-watchdog-" + id.substring(0, Math.min(12, id.length())));
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** {@code workspaceBytes <= 0} means no bound is configured — never kill. */
    static boolean exceedsQuota(long sizeBytes, long workspaceBytes) {
        return workspaceBytes > 0 && sizeBytes > workspaceBytes;
    }

    /** Best-effort — an {@link IOException} mid-walk (a file removed/rewritten
     *  concurrently by the running container) is not fatal; the caller simply
     *  sees a possibly-stale total on this poll and retries next interval. */
    static long workspaceSizeBytes(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0L;
        }
    }

    // --- helpers ---

    private java.util.OptionalInt inspectExitCode(String id) {
        try {
            InspectContainerResponse.ContainerState st = docker.inspectContainerCmd(id).exec().getState();
            Long code = st.getExitCodeLong();
            return code == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(code.intValue());
        } catch (RuntimeException e) {
            return java.util.OptionalInt.empty();
        }
    }

    private InspectContainerResponse inspectByNameQuietly(String name) {
        try {
            return docker.inspectContainerCmd(name).exec();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Container> listManaged() {
        return docker.listContainersCmd().withShowAll(true)
            .withLabelFilter(Map.of(LABEL_MANAGED, "true")).exec()
            .stream().sorted(Comparator.comparingLong(c -> c.getCreated() == null ? 0L : c.getCreated()))
            .toList();
    }

    private void killQuietly(String id, String signal) {
        try {
            docker.killContainerCmd(id).withSignal(signal).exec();
        } catch (RuntimeException e) {
            log.debug("kill {} {} ignored: {}", signal, id, e.toString());
        }
    }

    private void forceRemoveQuietly(String id) {
        try {
            docker.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
        } catch (RuntimeException e) {
            log.debug("removeContainer {} ignored: {}", id, e.toString());
        }
    }

    /** A {@code Bind} into a host path that does not yet exist is a known cause
     *  of the container-create call hanging (rather than failing fast) against
     *  Docker Desktop on Windows — its file-sharing / mount negotiation stalls
     *  indefinitely (thread-dump-confirmed) instead of erroring. The per-attempt
     *  workspace directory (§6: "created fresh, chown runner-uid, deleted in
     *  finally") must therefore exist on disk BEFORE the create call, every
     *  time — idempotent, so a caller that already created it pays only a stat. */
    private void ensureWorkspaceDirExists(Path workspaceHostDir) {
        try {
            Files.createDirectories(workspaceHostDir);
        } catch (IOException e) {
            throw new ContainerRunException("could not create workspace dir " + workspaceHostDir, e);
        }
        // The Worker process is not root and cannot chown this directory to an
        // arbitrary container uid (container.runner-uid, default 12000) — there
        // is no "chown to whatever uid the image happens to run as" available
        // without root. World-writable is the correct, safe substitute here:
        // this is a fresh, empty, execution-scoped scratch directory that is
        // deleted immediately after the attempt (never anything sensitive), so
        // functionally it achieves the same outcome (the container's non-root
        // user can write into it) without requiring elevated Worker privileges.
        try {
            Files.setPosixFilePermissions(workspaceHostDir, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException e) {
            // Windows (and any non-POSIX filesystem) — nothing to do; this box
            // never runs real containers against this path anyway.
        } catch (IOException e) {
            log.debug("could not set workspace dir permissions for {}: {}", workspaceHostDir, e.toString());
        }
    }

    private void deleteWorkspaceDir(UUID executionId) {
        Path dir = props.workspaceRootPath().resolve(executionId.toString());
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            log.warn("could not delete workspace dir {}: {}", dir, e.toString());
        }
    }

    private Duration staleAfter() {
        Duration max = props.maxRunTimeout() == null ? Duration.ofMinutes(30) : props.maxRunTimeout();
        return max.plus(Duration.ofMinutes(10));
    }

    private static String label(Container c, String key) {
        Map<String, String> l = c.getLabels();
        return l == null ? null : l.get(key);
    }

    private static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
