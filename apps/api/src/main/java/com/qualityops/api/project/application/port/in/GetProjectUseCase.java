package com.qualityops.api.project.application.port.in;

import com.qualityops.api.project.domain.Project;
import com.qualityops.api.project.dto.ProjectResponse;

import java.util.UUID;

public interface GetProjectUseCase {
    ProjectResponse get(UUID id, UUID orgId);

    /**
     * Returns the domain record so other modules can validate project
     * ownership without depending on this module's DTOs.
     */
    Project getDomain(UUID id, UUID orgId);
}
