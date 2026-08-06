package com.qualityops.api.result.dto;

import java.time.Instant;
import java.util.UUID;

public record AnalyticsResponse(
    UUID projectId,
    long totalRuns,
    long passedRuns,
    long failedRuns,
    double passRatePercent,
    Instant periodStart,
    Instant periodEnd
) {}
