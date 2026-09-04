package com.qualityops.api.result.dto;

import java.util.UUID;

/** ADR-008 §2 — one slowest-tests row. Durations in milliseconds. */
public record SlowTestRow(
    UUID testCaseId,
    String testCaseName,
    long samples,
    double avgMs,
    double p95Ms,
    double maxMs
) {}
