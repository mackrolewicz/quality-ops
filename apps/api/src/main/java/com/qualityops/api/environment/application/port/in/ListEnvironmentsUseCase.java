package com.qualityops.api.environment.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.environment.dto.EnvironmentResponse;

import java.util.UUID;

public interface ListEnvironmentsUseCase {
    PageResult<EnvironmentResponse> list(UUID projectId, UUID orgId, int page, int size);
}
