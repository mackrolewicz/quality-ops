package com.qualityops.api.result.application.port.in;

import com.qualityops.api.result.dto.SlowTestsResponse;

import java.util.UUID;

public interface GetSlowTestsUseCase {

    /** {@code days} / {@code limit} are expected pre-clamped to {@code [1, 90]} / {@code [1, 100]}
     *  by the controller; the service re-clamps defensively. Throws if {@code projectId} is not in {@code orgId}. */
    SlowTestsResponse getSlow(UUID projectId, UUID orgId, int days, int limit);
}
