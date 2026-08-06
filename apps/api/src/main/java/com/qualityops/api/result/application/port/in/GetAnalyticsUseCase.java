package com.qualityops.api.result.application.port.in;

import com.qualityops.api.result.dto.AnalyticsResponse;

import java.util.UUID;

public interface GetAnalyticsUseCase {

    AnalyticsResponse getAnalytics(UUID projectId, UUID orgId, int days);
}
