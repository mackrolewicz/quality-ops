package com.qualityops.api.environment.application.port.in;

import com.qualityops.api.environment.dto.CreateEnvironmentRequest;
import com.qualityops.api.environment.dto.EnvironmentResponse;

import java.util.UUID;

public interface CreateEnvironmentUseCase {
    EnvironmentResponse create(UUID projectId, CreateEnvironmentRequest request, UUID orgId);
}
