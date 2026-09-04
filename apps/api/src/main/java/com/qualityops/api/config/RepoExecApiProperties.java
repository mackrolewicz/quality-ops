package com.qualityops.api.config;

import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoResourceProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** ADR-009 §12 — the API-side subset of {@code qualityops.repo-exec.*}: the
 *  digest-pinned runner-image allowlist the preflight freezes from, the run
 *  timeout envelope, and the SCM host/credential settings. The Worker binds the
 *  rest through its own {@code RepoExecWorkerProperties}. Auto-registered by
 *  {@code @ConfigurationPropertiesScan}. */
@ConfigurationProperties("qualityops.repo-exec")
public record RepoExecApiProperties(
    boolean enabled,
    Images images,
    Duration defaultRunTimeout,
    Duration maxRunTimeout,
    RepoResourceProfile defaultResourceProfile,
    Scm scm,
    /** ADR-009 §8 — API-side double-guard: null out {@code repository_test_item.failure_message}
     *  on ingest when false (the Worker already redacts/suppresses; default false). */
    boolean persistReportSnippets
) {
    /** Digest-pinned refs, one per {@link FrameworkPreset}. No user images (ADR-009 §5). */
    public record Images(String playwright, String junit, String pytest, String cypress, String k6) {

        public String forPreset(FrameworkPreset preset) {
            return switch (preset) {
                case PLAYWRIGHT -> playwright;
                case JUNIT -> junit;
                case PYTEST -> pytest;
                case CYPRESS -> cypress;
                case K6 -> k6;
            };
        }
    }

    public record Scm(
        List<String> allowedHosts,
        boolean allowPrivateHosts,
        Duration httpTimeout,
        Duration refResolveTimeout,
        String credentialEnvPrefix,
        String credentialFile,
        /** Explicit API base URL override (GitHub Enterprise / self-managed / tests).
         *  Blank ⇒ derived from the connection host per ADR-009 §4. */
        String githubApiBase,
        String gitlabApiBase
    ) {}
}
