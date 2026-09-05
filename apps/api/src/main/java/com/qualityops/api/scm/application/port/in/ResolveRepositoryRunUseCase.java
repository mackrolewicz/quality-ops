package com.qualityops.api.scm.application.port.in;

import com.qualityops.events.RepoTestSnapshot;

import java.util.List;
import java.util.UUID;

/** ADR-009 §4 — ref→SHA resolution + snapshot freeze, invoked by
 *  {@code RunEnqueueService} for every {@code repoTest} case <em>before</em> the
 *  run row exists (domain rule #2). The input is scm-owned primitives so this
 *  module never depends on {@code testsuite} / {@code execution}. On any failure
 *  the caller's enqueue transaction rolls back — no orphan run. */
public interface ResolveRepositoryRunUseCase {

    ResolvedRepositoryRun resolve(ResolveRepositoryRunCommand command);

    record ResolveRepositoryRunCommand(UUID orgId, UUID projectId, RepositoryRunRequest request) {}

    /** Authored repo-test spec, as scm-owned primitives (String enums). */
    record RepositoryRunRequest(
        UUID connectionId,
        String requestedRef,
        String framework,
        String workingDir,
        List<String> command,
        String reportFormat,
        List<String> reportPaths,
        List<String> artifactGlobs,
        List<EnvVarValue> envVars,
        List<SecretRefValue> secretRefs,
        String resourceProfile,
        String networkPolicy,
        Integer timeoutSeconds
    ) {
        public record EnvVarValue(String name, String value) {}

        public record SecretRefValue(String name, String secretRef) {}
    }

    /** The frozen wire {@link RepoTestSnapshot} plus the frozen-columns row the
     *  {@code execution} module stages into {@code repository_run}. */
    record ResolvedRepositoryRun(RepoTestSnapshot snapshot, RepositoryRunFrozen stagedRow) {}
}
