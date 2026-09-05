package com.qualityops.api.result.dto;

import java.time.Instant;
import java.util.UUID;

/** ADR-008 §1 — one flaky-report row. {@code flakinessScore} is {@code 0.0} for all-pass
 *  or all-fail and {@code 1.0} for perfect alternation; {@code stabilityScore = 1 - flakiness}. */
public record FlakyTestRow(
    UUID testCaseId,
    String testCaseName,
    int runsAnalyzed,
    int passCount,
    int transitions,
    double flakinessScore,
    double stabilityScore,
    String lastStatus,
    Instant lastRunAt
) {}
