package com.qualityops.api.project.application.port.in;

import com.qualityops.api.project.dto.CreateProjectRequest;
import com.qualityops.api.project.dto.ProjectResponse;

import java.util.UUID;

public interface CreateProjectUseCase {
    ProjectResponse create(CreateProjectRequest request, UUID orgId, UUID createdBy);
}
