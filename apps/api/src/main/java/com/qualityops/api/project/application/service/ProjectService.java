package com.qualityops.api.project.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.project.application.port.in.CreateProjectUseCase;
import com.qualityops.api.project.application.port.in.DeleteProjectUseCase;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.application.port.in.ListProjectsUseCase;
import com.qualityops.api.project.application.port.in.UpdateProjectUseCase;
import com.qualityops.api.project.application.port.out.ProjectRepository;
import com.qualityops.api.project.domain.Project;
import com.qualityops.api.project.dto.CreateProjectRequest;
import com.qualityops.api.project.dto.ProjectResponse;
import com.qualityops.api.project.dto.UpdateProjectRequest;
import com.qualityops.api.project.exception.ProjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class ProjectService implements CreateProjectUseCase, ListProjectsUseCase,
    GetProjectUseCase, UpdateProjectUseCase, DeleteProjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    public ProjectResponse create(CreateProjectRequest request, UUID orgId, UUID createdBy) {
        var now = Instant.now();
        var project = new Project(
            UUID.randomUUID(),
            orgId,
            request.name(),
            request.description(),
            request.slug(),
            createdBy,
            now,
            now,
            null
        );
        var saved = projectRepository.save(project);
        log.info("Created project {} in org {}", saved.id(), orgId);
        return ProjectResponse.from(saved);
    }

    @Override
    public PageResult<ProjectResponse> list(UUID orgId, int page, int size) {
        var result = projectRepository.findAllByOrgId(orgId, page, size);
        return new PageResult<>(
            result.items().stream().map(ProjectResponse::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @Override
    public ProjectResponse get(UUID id, UUID orgId) {
        return ProjectResponse.from(getDomain(id, orgId));
    }

    @Override
    public Project getDomain(UUID id, UUID orgId) {
        return projectRepository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));
    }

    @Override
    public ProjectResponse update(UUID id, UpdateProjectRequest request, UUID orgId) {
        var existing = getDomain(id, orgId);
        var updated = new Project(
            existing.id(),
            existing.orgId(),
            request.name(),
            request.description(),
            existing.slug(),
            existing.createdBy(),
            existing.createdAt(),
            Instant.now(),
            existing.deletedAt()
        );
        var saved = projectRepository.save(updated);
        return ProjectResponse.from(saved);
    }

    @Override
    public void delete(UUID id, UUID orgId) {
        getDomain(id, orgId);
        projectRepository.softDelete(id, orgId, Instant.now());
        log.info("Deleted project {} in org {}", id, orgId);
    }
}
