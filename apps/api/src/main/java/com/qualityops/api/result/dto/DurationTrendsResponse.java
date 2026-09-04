package com.qualityops.api.result.dto;

import java.util.List;
import java.util.UUID;

/** ADR-008 §2 — one {@link TrendPoint} per day over the requested window, zero-filled. */
public record DurationTrendsResponse(UUID projectId, int days, List<TrendPoint> points) {}
