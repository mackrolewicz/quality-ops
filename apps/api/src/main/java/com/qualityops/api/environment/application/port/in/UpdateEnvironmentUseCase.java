package com.qualityops.api.environment.application.port.in;

import com.qualityops.api.environment.dto.EnvironmentResponse;
import com.qualityops.api.environment.dto.UpdateEnvironmentRequest;

import java.util.UUID;

public interface UpdateEnvironmentUseCase {
    EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request, UUID orgId);
}
