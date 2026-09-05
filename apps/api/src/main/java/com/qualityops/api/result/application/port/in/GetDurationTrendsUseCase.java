package com.qualityops.api.result.application.port.in;

import com.qualityops.api.result.dto.DurationTrendsResponse;

import java.util.UUID;

public interface GetDurationTrendsUseCase {

    /** {@code days} is expected pre-clamped to {@code [1, 90]} by the controller;
     *  the service re-clamps defensively. Throws if {@code projectId} is not in {@code orgId}. */
    DurationTrendsResponse getTrends(UUID projectId, UUID orgId, int days);
}
