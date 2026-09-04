package com.qualityops.api.result.application.port.in;

import com.qualityops.api.result.dto.FlakyAnalyticsResponse;

import java.util.UUID;

public interface GetFlakyAnalyticsUseCase {

    /** {@code window} is expected pre-clamped to {@code [5, 50]} by the controller;
     *  the service re-clamps defensively. Throws if {@code projectId} is not in {@code orgId}. */
    FlakyAnalyticsResponse getFlaky(UUID projectId, UUID orgId, int window);
}
