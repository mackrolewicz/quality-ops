package com.qualityops.worker.support;

import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Artifacts;
import com.qualityops.worker.config.WorkerExecutionProperties.Browser;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Retry;
import com.qualityops.worker.config.WorkerExecutionProperties.Secrets;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Test-only factory for {@link WorkerExecutionProperties} so arity changes touch one file. */
public final class TestProps {

    private TestProps() {}

    public static Browser browser() {
        return new Browser(true, true,
            Duration.ofSeconds(60), Duration.ofMinutes(3), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5),
            false, true, true,
            System.getProperty("java.io.tmpdir") + "/qualityops-browser-test", 5_242_880L, Duration.ofHours(1));
    }

    public static Browser browser(Path dir, Duration retention) {
        return new Browser(true, true,
            Duration.ofSeconds(60), Duration.ofMinutes(3), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5),
            false, true, true,
            dir.toString(), 5_242_880L, retention);
    }

    /** Artifacts OFF by default — no unit/IT build constructs a MinIO client (ADR-005 §5, watch-out #13). */
    public static Artifacts artifacts() {
        return new Artifacts(false, "http://localhost:9000", "qualityops-artifacts",
            "qualityops", "qualityops-dev-secret", "us-east-1", Artifacts.Sse.S3, true,
            Duration.ofSeconds(10), 10_485_760L, 30,
            System.getProperty("java.io.tmpdir") + "/qualityops-artifact-staging-test",
            Duration.ofHours(2), false, false);
    }

    public static Artifacts artifacts(boolean enabled, String endpoint, String accessKey, String secretKey,
                                      Path stagingDir, boolean uploadSecretCases) {
        return new Artifacts(enabled, endpoint, "qualityops-artifacts", accessKey, secretKey, "us-east-1",
            Artifacts.Sse.S3, true, Duration.ofSeconds(10), 10_485_760L, 30,
            stagingDir.toString(), Duration.ofHours(2), false, uploadSecretCases);
    }

    public static Retry retry() {
        return new Retry(true, 2, List.of("TIMEOUT", "ERROR"), Duration.ZERO);
    }

    public static Retry retry(boolean enabled, int maxAttempts) {
        return new Retry(enabled, maxAttempts, List.of("TIMEOUT", "ERROR"), Duration.ZERO);
    }

    public static Secrets secrets() {
        return new Secrets("QUALITYOPS_SECRET_", null);
    }

    public static WorkerExecutionProperties defaults(Mode mode) {
        return defaults(mode, Duration.ofMinutes(5), null, null, false);
    }

    public static WorkerExecutionProperties defaults(Mode mode, Duration budget) {
        return defaults(mode, budget, null, null, false);
    }

    public static WorkerExecutionProperties defaults(Mode mode, Duration budget,
            Ssrf ssrf, Redaction redaction, boolean persistBodySnippets) {
        return defaults(mode, budget, ssrf, redaction, persistBodySnippets, browser());
    }

    public static WorkerExecutionProperties defaults(Mode mode, Duration budget,
            Ssrf ssrf, Redaction redaction, boolean persistBodySnippets, Browser browser) {
        return new WorkerExecutionProperties(mode, Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(5), 1_048_576, 4096, budget, Duration.ofMinutes(2),
            Duration.ofDays(14), false, ssrf, redaction, persistBodySnippets, browser,
            artifacts(), retry(), secrets());
    }

    public static WorkerExecutionProperties defaults(Mode mode, Duration budget, Artifacts artifacts,
            Retry retry, Secrets secrets) {
        return new WorkerExecutionProperties(mode, Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(5), 1_048_576, 4096, budget, Duration.ofMinutes(2),
            Duration.ofDays(14), false, null, null, false, browser(),
            artifacts, retry, secrets);
    }
}
