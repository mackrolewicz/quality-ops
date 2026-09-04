package com.qualityops.events;

import java.util.List;
import java.util.UUID;

/** Frozen repository-run spec for one snapshot case (ADR-009). Nullable on
 *  {@link TestCaseSnapshotItem} — absent ⇒ the case is not a repository run.
 *  {@code commitSha} is a resolved 40-hex SHA and {@code runnerImageRef} a
 *  digest-pinned ref, both frozen by the API before the run exists
 *  (domain rule #2). Carries no plaintext secret or credential — only opaque
 *  keys the Worker resolves at execution time. */
public record RepoTestSnapshot(
        UUID repositoryConnectionId,
        RepositoryProvider provider,          // GITHUB | GITLAB
        String repoHost,                      // canonical host, e.g. "github.com"
        String repoPath,                      // canonical "owner/name"
        String requestedRef,                  // branch/tag/short-sha as authored
        String commitSha,                     // RESOLVED 40-hex — frozen, immutable
        RepoRefType refType,                  // BRANCH | TAG | COMMIT
        FrameworkPreset framework,            // PLAYWRIGHT | JUNIT | PYTEST | CYPRESS | K6
        String runnerImageRef,               // digest-pinned, frozen from the API allowlist
        String workingDir,                    // nullable
        List<String> command,                // argv — never null/empty, never shell
        RepoReportFormat reportFormat,        // JUNIT_XML | K6_SUMMARY_JSON
        List<String> reportPaths,             // globs, workspace-relative
        List<String> artifactGlobs,           // globs, workspace-relative
        List<EnvVar> environmentVars,         // non-secret env
        List<SecretEnvVar> secretVars,        // secret-backed env — resolved by the Worker
        String credentialRef,                 // opaque [A-Z0-9_]{1,64}, nullable (public repo)
        RepoResourceProfile resourceProfile,  // SMALL | MEDIUM | LARGE
        RepoNetworkPolicy networkPolicy,      // ISOLATED | EGRESS
        int timeoutSeconds
) {}
