package com.qualityops.api.environment.application.port.in;

import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.dto.EnvironmentResponse;

import java.util.UUID;

public interface GetEnvironmentUseCase {
    EnvironmentResponse get(UUID id, UUID orgId);

    /**
     * Returns the domain record so other modules (e.g. execution) can
     * validate environment ownership without depending on this module's DTOs.
     */
    Environment getDomain(UUID id, UUID orgId);
}
