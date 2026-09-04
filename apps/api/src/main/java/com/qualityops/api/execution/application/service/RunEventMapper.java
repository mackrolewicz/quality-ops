package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.domain.ApiRequestSpec;
import com.qualityops.api.execution.domain.BrowserTestSpec;
import com.qualityops.api.execution.domain.RepoTestSpec;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.ResolvedRepositoryRun;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RunRequestedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure mapping — no I/O. Owns the test-suite-domain -> execution-domain -> wire
 *  conversions that used to live in RunService, so both RunEnqueueService (build
 *  the frozen event) and any future caller can reuse them. Keeps the
 *  "exactly one of value / secretRef" guards as server-side defence-in-depth. */
@Component
public class RunEventMapper {

    private static final Pattern SHA_40 = Pattern.compile("[0-9a-fA-F]{40}");

    /** Builds the RunRequestedEvent that RunEnqueueService freezes into
     *  run_queue.requested_event_json. {@code frozenRepoByCase} carries the
     *  preflight-resolved {@link RepoTestSnapshot} for each repository case
     *  (empty for a run with no repository case). */
    public RunRequestedEvent toRequestedEvent(
            UUID orgId, UUID runId, UUID executionId, UUID correlationId, Instant occurredAt,
            UUID projectId, UUID suiteId, UUID environmentId, UUID triggeredBy,
            List<TestCaseSnapshotItem> domainSnapshot,
            Map<UUID, RepoTestSnapshot> frozenRepoByCase) {
        return new RunRequestedEvent(
            UUID.randomUUID(), correlationId, orgId, runId, executionId, occurredAt,
            RunRequestedEvent.SCHEMA_VERSION,
            projectId, suiteId, environmentId, triggeredBy,
            toWireSnapshot(domainSnapshot, frozenRepoByCase));
    }

    private List<com.qualityops.events.TestCaseSnapshotItem> toWireSnapshot(
            List<TestCaseSnapshotItem> domainItems, Map<UUID, RepoTestSnapshot> frozenRepoByCase) {
        return domainItems.stream()
            .map(i -> new com.qualityops.events.TestCaseSnapshotItem(
                i.testCaseId(), i.name(), i.orderIndex(),
                toWireSpec(i.apiRequest()), toWireBrowser(i.browserTest()),
                frozenRepoByCase.get(i.testCaseId())))
            .toList();
    }

    /** Test-suite repository spec -> execution domain spec (authored; no resolved
     *  SHA). Null-safe. */
    public RepoTestSpec toRepoSpec(com.qualityops.api.testsuite.domain.RepoTestSpec src) {
        if (src == null) {
            return null;
        }
        var env = src.environmentVars() == null ? List.<RepoTestSpec.EnvVarSpec>of()
            : src.environmentVars().stream()
                .map(e -> new RepoTestSpec.EnvVarSpec(e.name(), e.value()))
                .toList();
        var secrets = src.secretVars() == null ? List.<RepoTestSpec.SecretVarSpec>of()
            : src.secretVars().stream()
                .map(s -> new RepoTestSpec.SecretVarSpec(s.name(), s.secretRef()))
                .toList();
        return new RepoTestSpec(src.repositoryConnectionId(), src.requestedRef(), src.framework(),
            src.workingDir(), src.command(), src.reportFormat(), src.reportPaths(), src.artifactGlobs(),
            env, secrets, src.resourceProfile(), src.networkPolicy(), src.timeoutSeconds());
    }

    /** The frozen wire {@link RepoTestSnapshot} from the enqueue-time preflight,
     *  re-validated as server-side defence-in-depth: a resolved 40-hex commit
     *  SHA, a digest-pinned runner image, and a non-empty argv command. */
    public RepoTestSnapshot toWireRepo(RepoTestSpec authored, ResolvedRepositoryRun preflight) {
        RepoTestSnapshot snapshot = preflight.snapshot();
        if (snapshot.commitSha() == null || !SHA_40.matcher(snapshot.commitSha()).matches()) {
            throw new IllegalStateException("preflight produced no resolved 40-hex commit SHA");
        }
        if (snapshot.runnerImageRef() == null || !snapshot.runnerImageRef().contains("@sha256:")) {
            throw new IllegalStateException("preflight produced a non-digest-pinned runner image");
        }
        if (snapshot.command() == null || snapshot.command().isEmpty()) {
            throw new IllegalStateException("repository run command (argv) must not be empty");
        }
        if (authored != null && authored.repositoryConnectionId() != null
                && !authored.repositoryConnectionId().equals(snapshot.repositoryConnectionId())) {
            throw new IllegalStateException("preflight resolved a different repository connection");
        }
        return snapshot;
    }

    /** Test-suite domain spec -> execution domain spec. Null-safe. */
    public ApiRequestSpec toSpec(com.qualityops.api.testsuite.domain.ApiRequestSpec src) {
        if (src == null) {
            return null;
        }
        var headers = src.headers() == null ? List.<ApiRequestSpec.HeaderPair>of()
            : src.headers().stream()
                .map(h -> new ApiRequestSpec.HeaderPair(h.name(), h.value(), h.secretRef()))
                .toList();
        var assertions = src.assertions() == null ? List.<ApiRequestSpec.ApiAssertionSpec>of()
            : src.assertions().stream()
                .map(a -> new ApiRequestSpec.ApiAssertionSpec(a.type(), a.target(), a.expected()))
                .toList();
        return new ApiRequestSpec(src.method(), src.url(), headers, src.body(),
            src.expectedStatus(), src.timeoutMillis(), src.maxResponseBytes(), assertions);
    }

    /** Test-suite browser spec -> execution browser spec. Null-safe. */
    public BrowserTestSpec toBrowserSpec(com.qualityops.api.testsuite.domain.BrowserTestSpec src) {
        if (src == null) {
            return null;
        }
        var steps = src.steps() == null ? List.<BrowserTestSpec.BrowserStepSpec>of()
            : src.steps().stream()
                .map(s -> new BrowserTestSpec.BrowserStepSpec(
                    s.action(), toExecSelector(s.target()), s.value(), s.key(), s.secretRef()))
                .toList();
        var assertions = src.assertions() == null ? List.<BrowserTestSpec.BrowserAssertionSpec>of()
            : src.assertions().stream()
                .map(a -> new BrowserTestSpec.BrowserAssertionSpec(
                    a.type(), toExecSelector(a.target()), a.expected()))
                .toList();
        return new BrowserTestSpec(src.startUrl(), steps, assertions,
            src.testTimeoutMillis(), src.stepTimeoutMillis(), src.navigationTimeoutMillis());
    }

    private static BrowserTestSpec.SelectorSpec toExecSelector(
            com.qualityops.api.testsuite.domain.BrowserTestSpec.SelectorSpec s) {
        return s == null ? null
            : new BrowserTestSpec.SelectorSpec(s.strategy(), s.value(), s.roleName(), s.accessibleName());
    }

    /** Execution browser spec -> wire snapshot. Null-safe. */
    private static com.qualityops.events.BrowserTestSnapshot toWireBrowser(BrowserTestSpec src) {
        if (src == null) {
            return null;
        }
        var steps = src.steps().stream()
            .map(RunEventMapper::toWireStep)
            .toList();
        var assertions = src.assertions().stream()
            .map(a -> new com.qualityops.events.BrowserAssertion(
                com.qualityops.events.BrowserAssertion.Type.valueOf(a.type()),
                toWireSelector(a.target()), a.expected()))
            .toList();
        return new com.qualityops.events.BrowserTestSnapshot(src.startUrl(), steps, assertions,
            src.testTimeoutMillis(), src.stepTimeoutMillis(), src.navigationTimeoutMillis());
    }

    private static com.qualityops.events.Selector toWireSelector(BrowserTestSpec.SelectorSpec s) {
        return s == null ? null : new com.qualityops.events.Selector(
            com.qualityops.events.Selector.Strategy.valueOf(s.strategy()),
            s.value(), s.roleName(), s.accessibleName());
    }

    private static com.qualityops.events.SecretRef toWireSecret(String key) {
        return key == null || key.isBlank() ? null : new com.qualityops.events.SecretRef(key);
    }

    /** Freezes only the secret KEY onto the wire — never resolves plaintext.
     *  Enforces "exactly one of value / secretRef" as server-side defence-in-depth. */
    private static com.qualityops.events.BrowserStep toWireStep(BrowserTestSpec.BrowserStepSpec s) {
        var action = com.qualityops.events.BrowserStep.Action.valueOf(s.action());
        var secret = toWireSecret(s.secretRef());
        if (action == com.qualityops.events.BrowserStep.Action.FILL
                && (s.value() != null) == (secret != null)) {
            throw new IllegalArgumentException("FILL step must set exactly one of value or secretRef");
        }
        return new com.qualityops.events.BrowserStep(
            action, toWireSelector(s.target()), s.value(), s.key(), secret);
    }

    private static com.qualityops.events.HttpHeader toWireHeader(ApiRequestSpec.HeaderPair h) {
        var secret = toWireSecret(h.secretRef());
        if ((h.value() != null) == (secret != null)) {
            throw new IllegalArgumentException(
                "header '" + h.name() + "' must set exactly one of value or secretRef");
        }
        return new com.qualityops.events.HttpHeader(h.name(), h.value(), secret);
    }

    /** Execution domain spec -> wire snapshot. Null-safe. */
    private static com.qualityops.events.ApiRequestSnapshot toWireSpec(ApiRequestSpec src) {
        if (src == null) {
            return null;
        }
        var headers = src.headers() == null ? List.<com.qualityops.events.HttpHeader>of()
            : src.headers().stream()
                .map(RunEventMapper::toWireHeader)
                .toList();
        var assertions = src.assertions() == null ? List.<com.qualityops.events.ApiAssertion>of()
            : src.assertions().stream()
                .map(a -> new com.qualityops.events.ApiAssertion(
                    com.qualityops.events.ApiAssertion.Type.valueOf(a.type()), a.target(), a.expected()))
                .toList();
        return new com.qualityops.events.ApiRequestSnapshot(src.method(), src.url(), headers, src.body(),
            src.expectedStatus(), src.timeoutMillis(), src.maxResponseBytes(), assertions);
    }
}
