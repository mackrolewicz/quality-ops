package com.qualityops.api.execution.domain;

import java.util.List;
import java.util.UUID;

/** Domain mirror of the test-suite-owned {@code RepoTestSpec}, frozen into a
 *  run's config snapshot (ADR-009). The <em>authored</em> spec — no resolved
 *  {@code commitSha} / runner image (those live on the wire
 *  {@code RepoTestSnapshot} and {@code repository_run}). Independent of the
 *  shared-events transport type (mirrors {@link ApiRequestSpec}). */
public record RepoTestSpec(
        UUID repositoryConnectionId,
        String requestedRef,
        String framework,          // PLAYWRIGHT | JUNIT | PYTEST | CYPRESS | K6
        String workingDir,         // nullable
        List<String> command,      // argv — never a shell string
        String reportFormat,       // JUNIT_XML | K6_SUMMARY_JSON
        List<String> reportPaths,
        List<String> artifactGlobs,
        List<EnvVarSpec> environmentVars,
        List<SecretVarSpec> secretVars,
        String resourceProfile,    // SMALL | MEDIUM | LARGE ; nullable
        String networkPolicy,      // ISOLATED | EGRESS ; nullable
        Integer timeoutSeconds     // nullable
) {
    public record EnvVarSpec(String name, String value) {}

    /** {@code secretRef} is the opaque credential key resolved by the Worker. */
    public record SecretVarSpec(String name, String secretRef) {}
}
