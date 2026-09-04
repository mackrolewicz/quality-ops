package com.qualityops.worker.execution.application.port.out;

import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.exception.ContainerRunException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADR-009 §1 — the control-plane driver's one seam over container execution. The
 * Worker never runs repository code in-JVM; it speaks this port to create /
 * start / wait / logs / kill / rm one hardened sibling container per call and
 * reads files from a bind-mounted per-attempt workspace. Phase 5 swaps
 * {@code DockerContainerRunner} for a {@code KubernetesJobRunner} behind the same
 * interface with no change to the runner, resolver, events, or API.
 */
public interface ContainerRunnerPort {

    /** Create ONE container from an allowlisted, digest-pinned image, start it,
     *  stream stdout/stderr to the sink, wait up to {@code spec.timeout()},
     *  return the exit result. NEVER pulls or runs a non-allowlisted image.
     *  Enforces every {@code HostConfig} limit in ADR §6. */
    ContainerRunResult run(ContainerRunSpec spec, LogSink logs, CancellationToken cancel)
        throws ContainerRunException;

    /** Best-effort unconditional teardown: force-remove any managed container +
     *  the per-attempt workspace directory for this {@code executionId}. Called
     *  from a {@code finally} and on a cooperative cancel. */
    void cleanup(UUID executionId);

    /** Startup + periodic: force-remove managed containers whose attempt is
     *  COMPLETED, or older than the run wall-clock budget, or not in
     *  {@code liveExecutionIds}. Returns the count removed. */
    int sweepOrphans(Set<UUID> liveExecutionIds);

    /** Receives one redacted stdout/stderr line at a time. */
    @FunctionalInterface
    interface LogSink {
        void accept(String line);
    }

    record ContainerRunSpec(
        UUID executionId, int attemptEpoch, String phase, // "checkout" | "framework"
        String imageRef, List<String> entrypoint, List<String> command,
        String workingDir, Map<String, String> env,
        Path workspaceHostDir, ResourceLimits limits,
        NetworkMode network, Duration timeout,
        Map<String, String> labels,
        Map<Path, String> secretBinds) { // host path -> container absolute path, ALWAYS read-only

        /** Convenience — no secret binds. Keeps the pre-ADR-009-fix-1 13-arg
         *  call sites (e.g. {@code frameworkSpec}) compiling unchanged. */
        public ContainerRunSpec(UUID executionId, int attemptEpoch, String phase, String imageRef,
                                List<String> entrypoint, List<String> command, String workingDir,
                                Map<String, String> env, Path workspaceHostDir, ResourceLimits limits,
                                NetworkMode network, Duration timeout, Map<String, String> labels) {
            this(executionId, attemptEpoch, phase, imageRef, entrypoint, command, workingDir, env,
                workspaceHostDir, limits, network, timeout, labels, Map.of());
        }

        public String containerName() {
            return "qualityops-run-" + executionId + "-" + attemptEpoch + "-" + phase;
        }
    }

    record ResourceLimits(long memoryBytes, long nanoCpus, int pidsLimit,
                          long tmpfsBytes, long workspaceBytes,
                          long nofileSoft, long nofileHard) {}

    enum NetworkMode { NONE, EGRESS }

    record ContainerRunResult(int exitCode, boolean timedOut, boolean cancelled,
                              Instant startedAt, Instant finishedAt) {}
}
