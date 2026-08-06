package com.qualityops.api.environment.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.environment.application.port.in.CreateEnvironmentUseCase;
import com.qualityops.api.environment.application.port.in.DeleteEnvironmentUseCase;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.application.port.in.ListEnvironmentsUseCase;
import com.qualityops.api.environment.application.port.in.UpdateEnvironmentUseCase;
import com.qualityops.api.environment.application.port.out.EnvironmentRepository;
import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.dto.CreateEnvironmentRequest;
import com.qualityops.api.environment.dto.EnvironmentResponse;
import com.qualityops.api.environment.dto.UpdateEnvironmentRequest;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class EnvironmentService implements CreateEnvironmentUseCase, ListEnvironmentsUseCase,
    GetEnvironmentUseCase, UpdateEnvironmentUseCase, DeleteEnvironmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final EnvironmentRepository environmentRepository;
    private final GetProjectUseCase getProjectUseCase;

    public EnvironmentService(EnvironmentRepository environmentRepository, GetProjectUseCase getProjectUseCase) {
        this.environmentRepository = environmentRepository;
        this.getProjectUseCase = getProjectUseCase;
    }

    @Override
    public EnvironmentResponse create(UUID projectId, CreateEnvironmentRequest request, UUID orgId) {
        getProjectUseCase.getDomain(projectId, orgId);
        var now = Instant.now();
        var environment = new Environment(
            UUID.randomUUID(),
            orgId,
            projectId,
            request.name(),
            request.baseUrl(),
            request.type(),
            EnvironmentStatus.ACTIVE,
            now,
            now,
            null
        );
        var saved = environmentRepository.save(environment);
        log.info("Created environment {} for project {} in org {}", saved.id(), projectId, orgId);
        return EnvironmentResponse.from(saved);
    }

    @Override
    public PageResult<EnvironmentResponse> list(UUID projectId, UUID orgId, int page, int size) {
        getProjectUseCase.getDomain(projectId, orgId);
        var result = environmentRepository.findAllByProjectIdAndOrgId(projectId, orgId, page, size);
        return new PageResult<>(
            result.items().stream().map(EnvironmentResponse::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @Override
    public EnvironmentResponse get(UUID id, UUID orgId) {
        return EnvironmentResponse.from(getDomain(id, orgId));
    }

    @Override
    public Environment getDomain(UUID id, UUID orgId) {
        return environmentRepository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new EnvironmentNotFoundException("Environment not found: " + id));
    }

    @Override
    public EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request, UUID orgId) {
        var existing = getDomain(id, orgId);
        var updated = new Environment(
            existing.id(),
            existing.orgId(),
            existing.projectId(),
            request.name(),
            request.baseUrl(),
            request.type(),
            request.status(),
            existing.createdAt(),
            Instant.now(),
            existing.deletedAt()
        );
        var saved = environmentRepository.save(updated);
        return EnvironmentResponse.from(saved);
    }

    @Override
    public void delete(UUID id, UUID orgId) {
        getDomain(id, orgId);
        environmentRepository.softDelete(id, orgId, Instant.now());
        log.info("Deleted environment {} in org {}", id, orgId);
    }
}
