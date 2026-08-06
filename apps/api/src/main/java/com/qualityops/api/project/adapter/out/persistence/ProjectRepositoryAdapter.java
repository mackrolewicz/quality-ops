package com.qualityops.api.project.adapter.out.persistence;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.project.application.port.out.ProjectRepository;
import com.qualityops.api.project.domain.Project;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class ProjectRepositoryAdapter implements ProjectRepository {

    private final ProjectJpaRepository jpa;

    ProjectRepositoryAdapter(ProjectJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Project save(Project project) {
        return jpa.save(ProjectEntity.fromDomain(project)).toDomain();
    }

    @Override
    public Optional<Project> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId).map(ProjectEntity::toDomain);
    }

    @Override
    public PageResult<Project> findAllByOrgId(UUID orgId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllByOrgIdAndDeletedAtIsNull(orgId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(ProjectEntity::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    @Override
    public void softDelete(UUID id, UUID orgId, Instant deletedAt) {
        jpa.softDelete(id, orgId, deletedAt);
    }
}
