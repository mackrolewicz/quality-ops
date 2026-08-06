package com.qualityops.api.project.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.project.dto.ProjectResponse;

import java.util.UUID;

public interface ListProjectsUseCase {
    PageResult<ProjectResponse> list(UUID orgId, int page, int size);
}
