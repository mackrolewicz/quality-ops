package com.qualityops.api.project.application.port.in;

import com.qualityops.api.project.dto.ProjectResponse;
import com.qualityops.api.project.dto.UpdateProjectRequest;

import java.util.UUID;

public interface UpdateProjectUseCase {
    ProjectResponse update(UUID id, UpdateProjectRequest request, UUID orgId);
}
