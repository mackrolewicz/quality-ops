package com.qualityops.api.project.application.port.in;

import java.util.UUID;

public interface DeleteProjectUseCase {
    void delete(UUID id, UUID orgId);
}
