package com.qualityops.api.environment.adapter.out.persistence;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.environment.application.port.out.EnvironmentRepository;
import com.qualityops.api.environment.domain.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class EnvironmentRepositoryAdapter implements EnvironmentRepository {

    private final EnvironmentJpaRepository jpa;

    EnvironmentRepositoryAdapter(EnvironmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Environment save(Environment environment) {
        return jpa.save(EnvironmentEntity.fromDomain(environment)).toDomain();
    }

    @Override
    public Optional<Environment> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId).map(EnvironmentEntity::toDomain);
    }

    @Override
    public PageResult<Environment> findAllByProjectIdAndOrgId(UUID projectId, UUID orgId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(EnvironmentEntity::toDomain).toList(),
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
