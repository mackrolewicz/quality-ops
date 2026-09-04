package com.qualityops.api.scm.application.port.in;

import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RepositoryProvider;

import java.util.List;
import java.util.UUID;

/** ADR-009 §3/§4 — the immutable {@code repository_run} columns frozen by the API
 *  at enqueue (domain rule #2). Staged by the preflight and written by the
 *  {@code execution} module's {@code RepositoryRunWriteUseCase}; execution
 *  telemetry columns are filled later by the lifecycle / result consumers. */
public record RepositoryRunFrozen(
    UUID repositoryConnectionId,
    RepositoryProvider provider,
    String repoHost,
    String repoPath,
    String requestedRef,
    String commitSha,
    RepoRefType refType,
    FrameworkPreset framework,
    String runnerImageRef,
    String workingDir,
    List<String> command,
    RepoReportFormat reportFormat,
    List<String> reportPaths,
    List<String> artifactGlobs,
    RepoResourceProfile resourceProfile,
    RepoNetworkPolicy networkPolicy,
    int timeoutSeconds
) {
    /** Rebuild the frozen-columns row from an already-frozen wire snapshot
     *  (ADR-007 retry replay — no re-freeze, no re-preflight). */
    public static RepositoryRunFrozen fromSnapshot(RepoTestSnapshot s) {
        return new RepositoryRunFrozen(
            s.repositoryConnectionId(), s.provider(), s.repoHost(), s.repoPath(),
            s.requestedRef(), s.commitSha(), s.refType(), s.framework(), s.runnerImageRef(),
            s.workingDir(), s.command(), s.reportFormat(), s.reportPaths(), s.artifactGlobs(),
            s.resourceProfile(), s.networkPolicy(), s.timeoutSeconds());
    }
}
