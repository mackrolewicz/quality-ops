package com.qualityops.worker.execution.domain;

import java.nio.file.Path;
import java.util.List;

public record BrowserRunOutcome(
        List<BrowserStepOutcome> steps,
        List<BrowserAssertionOutcome> assertions,
        String finalUrl,                 // redacted
        Path screenshot,                 // nullable
        long screenshotBytes,
        Path trace,                      // nullable
        long traceBytes,
        Status status,
        String faultReason) {            // nullable, only when status == FAULT

    public enum Status { COMPLETED, TIMED_OUT, FAULT }
}
