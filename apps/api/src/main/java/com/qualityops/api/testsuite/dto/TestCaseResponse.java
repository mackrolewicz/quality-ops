package com.qualityops.api.testsuite.dto;

import com.qualityops.api.testsuite.domain.ApiRequestSpec;
import com.qualityops.api.testsuite.domain.BrowserTestSpec;
import com.qualityops.api.testsuite.domain.RepoTestSpec;
import com.qualityops.api.testsuite.domain.TestCase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TestCaseResponse(
    UUID id,
    UUID suiteId,
    String name,
    String description,
    int orderIndex,
    Instant createdAt,
    Instant updatedAt,
    ApiRequestPayload apiRequest,
    BrowserTestPayload browserTest,
    RepoTestPayload repoTest
) {
    public static TestCaseResponse from(TestCase testCase) {
        return new TestCaseResponse(
            testCase.id(),
            testCase.suiteId(),
            testCase.name(),
            testCase.description(),
            testCase.orderIndex(),
            testCase.createdAt(),
            testCase.updatedAt(),
            toPayload(testCase.apiRequest()),
            toPayload(testCase.browserTest()),
            toPayload(testCase.repoTest())
        );
    }

    private static ApiRequestPayload toPayload(ApiRequestSpec spec) {
        if (spec == null) {
            return null;
        }
        var headers = spec.headers() == null ? List.<ApiRequestPayload.HeaderPayload>of()
            : spec.headers().stream()
                .map(h -> new ApiRequestPayload.HeaderPayload(h.name(), h.value(), h.secretRef()))
                .toList();
        var assertions = spec.assertions() == null ? List.<ApiRequestPayload.AssertionPayload>of()
            : spec.assertions().stream()
                .map(a -> new ApiRequestPayload.AssertionPayload(a.type(), a.target(), a.expected()))
                .toList();
        return new ApiRequestPayload(spec.method(), spec.url(), headers, spec.body(),
            spec.expectedStatus(), spec.timeoutMillis(), spec.maxResponseBytes(), assertions);
    }

    private static BrowserTestPayload toPayload(BrowserTestSpec spec) {
        if (spec == null) {
            return null;
        }
        var steps = spec.steps().stream()
            .map(s -> new BrowserTestPayload.BrowserStepPayload(
                s.action(), toSelectorPayload(s.target()), s.value(), s.key(), s.secretRef()))
            .toList();
        var assertions = spec.assertions().stream()
            .map(a -> new BrowserTestPayload.BrowserAssertionPayload(
                a.type(), toSelectorPayload(a.target()), a.expected()))
            .toList();
        return new BrowserTestPayload(spec.startUrl(), steps, assertions,
            spec.testTimeoutMillis(), spec.stepTimeoutMillis(), spec.navigationTimeoutMillis());
    }

    private static BrowserTestPayload.SelectorPayload toSelectorPayload(BrowserTestSpec.SelectorSpec s) {
        return s == null ? null
            : new BrowserTestPayload.SelectorPayload(s.strategy(), s.value(), s.roleName(), s.accessibleName());
    }

    private static RepoTestPayload toPayload(RepoTestSpec spec) {
        if (spec == null) {
            return null;
        }
        var env = spec.environmentVars() == null ? List.<RepoTestPayload.EnvVarPayload>of()
            : spec.environmentVars().stream()
                .map(e -> new RepoTestPayload.EnvVarPayload(e.name(), e.value()))
                .toList();
        var secrets = spec.secretVars() == null ? List.<RepoTestPayload.SecretVarPayload>of()
            : spec.secretVars().stream()
                .map(s -> new RepoTestPayload.SecretVarPayload(s.name(), s.secretRef()))
                .toList();
        return new RepoTestPayload(spec.repositoryConnectionId(), spec.requestedRef(), spec.framework(),
            spec.workingDir(), spec.command(), spec.reportFormat(), spec.reportPaths(),
            spec.artifactGlobs(), env, secrets, spec.resourceProfile(), spec.networkPolicy(),
            spec.timeoutSeconds());
    }
}
