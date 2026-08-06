package com.qualityops.api.project.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.project.domain.Project;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Project save(Project project);

    Optional<Project> findByIdAndOrgId(UUID id, UUID orgId);

    PageResult<Project> findAllByOrgId(UUID orgId, int page, int size);

    void softDelete(UUID id, UUID orgId, Instant deletedAt);
}
