package com.qualityops.api.testsuite.domain;

import java.util.List;
import java.util.UUID;

/** Test-suite-owned repository-execution spec authored on a {@link TestCase}
 *  (ADR-009 §11). Module-local, String-typed enums (mirrors {@link ApiRequestSpec}
 *  / {@link BrowserTestSpec}) — small duplication is acceptable to preserve
 *  module boundaries. No provider/host (derived from the connection) and no
 *  runner image (frozen from the API allowlist at enqueue, ADR-009 §5). */
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

    /** {@code secretRef} is the opaque credential key resolved by the Worker at
     *  execution time; no plaintext is ever stored here. */
    public record SecretVarSpec(String name, String secretRef) {}
}
