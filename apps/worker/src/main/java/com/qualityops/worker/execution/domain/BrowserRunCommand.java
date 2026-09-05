package com.qualityops.worker.execution.domain;

import com.qualityops.events.BrowserAssertion;
import com.qualityops.events.BrowserStep;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public record BrowserRunCommand(
        UUID executionId,
        UUID caseId,
        int attemptEpoch,
        String startUrl,                 // SSRF-validated by the caller
        List<BrowserStep> steps,
        List<BrowserAssertion> assertions,
        long stepTimeoutMillis,
        long navigationTimeoutMillis,
        long launchTimeoutMillis,
        long scenarioDeadlineMillis,     // wall-clock budget for the whole scenario
        boolean headless,
        boolean captureTrace,
        boolean screenshotOnFailure,
        Path artifactDir,
        long artifactMaxBytes,
        boolean blockPrivateSubresources,
        boolean persistTextSnippets,
        boolean secretCase) {   // any FILL step carries a secretValue ⇒ mask inputs, force trace off

    /** Convenience — no secret-bearing step. Keeps 17-arg call sites compiling. */
    public BrowserRunCommand(UUID executionId, UUID caseId, int attemptEpoch, String startUrl,
            List<BrowserStep> steps, List<BrowserAssertion> assertions, long stepTimeoutMillis,
            long navigationTimeoutMillis, long launchTimeoutMillis, long scenarioDeadlineMillis,
            boolean headless, boolean captureTrace, boolean screenshotOnFailure, Path artifactDir,
            long artifactMaxBytes, boolean blockPrivateSubresources, boolean persistTextSnippets) {
        this(executionId, caseId, attemptEpoch, startUrl, steps, assertions, stepTimeoutMillis,
            navigationTimeoutMillis, launchTimeoutMillis, scenarioDeadlineMillis, headless, captureTrace,
            screenshotOnFailure, artifactDir, artifactMaxBytes, blockPrivateSubresources,
            persistTextSnippets, false);
    }
}
