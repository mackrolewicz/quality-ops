package com.qualityops.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("qualityops.worker.execution")
public record WorkerExecutionProperties(
        Mode mode,
        Duration defaultRequestTimeout,
        Duration maxTimeout,
        Duration connectTimeout,
        long maxResponseBytes,
        int responseBodySampleBytes,
        Duration runWallClockBudget,
        Duration claimLease,
        Duration attemptRetention,
        boolean followRedirects,
        Ssrf ssrf,
        Redaction redaction,
        boolean persistBodySnippets,
        Browser browser,
        Artifacts artifacts,
        Retry retry,
        Secrets secrets
) {
    public enum Mode { SIMULATED, REAL, AUTO }

    /** Durable artifact storage (ADR-005 §1, §G). */
    public record Artifacts(
            boolean enabled,
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            String region,
            Sse sse,
            boolean pathStyleAccess,
            Duration uploadTimeout,
            long maxArtifactBytes,
            int retentionDays,
            String stagingDir,
            Duration stagingRetention,
            boolean bootstrapEnabled,
            boolean uploadSecretCases) {

        public enum Sse { NONE, S3 }

        public java.nio.file.Path stagingDirPath() {
            return java.nio.file.Path.of(stagingDir);
        }
    }

    /** Bounded in-run retry for transient TIMEOUT/ERROR (ADR-005 §3, §G). */
    public record Retry(
            boolean enabled,
            int maxAttempts,
            List<String> retryableStatuses,
            Duration backoff) {

        public boolean isRetryable(String caseStatusName) {
            return retryableStatuses != null && retryableStatuses.contains(caseStatusName);
        }
    }

    /** secretRef resolution sources (ADR-005 §4, §G). */
    public record Secrets(String envPrefix, String file) {}

    public record Ssrf(boolean allowPrivateTargets,
                       List<String> allowedHosts,
                       List<Integer> allowedPorts) {}

    public record Redaction(List<String> headerDenylist,
                            List<String> bodyPatterns) {}

    public record Browser(
            boolean enabled,
            boolean headless,
            Duration testTimeout,
            Duration maxTestTimeout,
            Duration stepTimeout,
            Duration navigationTimeout,
            Duration launchTimeout,
            Duration hardKillGrace,
            boolean captureTrace,
            boolean screenshotOnFailure,
            boolean blockPrivateSubresources,
            String artifactTempDir,
            long artifactMaxBytes,
            Duration artifactRetention) {

        public Duration effectiveTestTimeout(Integer perCaseMillis) {
            Duration base = perCaseMillis != null ? Duration.ofMillis(perCaseMillis) : testTimeout;
            if (base.compareTo(Duration.ofMillis(1)) < 0) {
                return Duration.ofMillis(1);
            }
            return base.compareTo(maxTestTimeout) > 0 ? maxTestTimeout : base;
        }

        public Duration effectiveStepTimeout(Integer perCaseMillis) {
            return clampToTest(perCaseMillis != null ? Duration.ofMillis(perCaseMillis) : stepTimeout);
        }

        public Duration effectiveNavigationTimeout(Integer perCaseMillis) {
            return clampToTest(perCaseMillis != null ? Duration.ofMillis(perCaseMillis) : navigationTimeout);
        }

        private Duration clampToTest(Duration base) {
            Duration cap = effectiveTestTimeout(null);
            if (base.compareTo(Duration.ofMillis(1)) < 0) {
                return Duration.ofMillis(1);
            }
            return base.compareTo(cap) > 0 ? cap : base;
        }

        public java.nio.file.Path artifactTempDirPath() {
            return java.nio.file.Path.of(artifactTempDir);
        }
    }

    public Duration effectiveTimeout(Integer perCaseMillis) {
        Duration base = perCaseMillis != null ? Duration.ofMillis(perCaseMillis) : defaultRequestTimeout;
        if (base.compareTo(Duration.ofMillis(1)) < 0) {
            return Duration.ofMillis(1);
        }
        return base.compareTo(maxTimeout) > 0 ? maxTimeout : base;
    }
}
