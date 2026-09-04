package com.qualityops.worker.execution.domain;

import java.util.List;

/** What the browser runner records about one case, carried on
 *  {@link CaseExecutionResult#browser()} for logging/diagnostics.
 *  All paths are temp-only; no artifact bytes are on the wire in 2B2. */
public record BrowserRunMetadata(
        List<BrowserStepOutcome> steps,
        List<BrowserAssertionOutcome> assertions,
        String finalUrl,
        int stepsPlanned,
        int stepsExecuted,
        String screenshotTempPath,   // nullable
        long screenshotBytes,
        String traceTempPath,        // nullable
        long traceBytes) {}
