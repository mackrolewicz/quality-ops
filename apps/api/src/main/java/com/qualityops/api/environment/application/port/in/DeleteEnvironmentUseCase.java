package com.qualityops.api.environment.application.port.in;

import java.util.UUID;

public interface DeleteEnvironmentUseCase {
    void delete(UUID id, UUID orgId);
}
