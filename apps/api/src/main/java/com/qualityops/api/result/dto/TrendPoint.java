package com.qualityops.api.result.dto;

import java.time.LocalDate;

/** ADR-008 §2 — daily run pass/fail counts plus avg / p95 case duration (ms). */
public record TrendPoint(
    LocalDate date,
    long totalRuns,
    long passedRuns,
    long failedRuns,
    double avgDurationMs,
    double p95DurationMs
) {}
