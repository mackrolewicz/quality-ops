package com.qualityops.api.scm.adapter.out.persistence;

import com.qualityops.api.scm.application.port.out.RepositoryConnectionRepository;
import com.qualityops.api.scm.domain.RepositoryConnection;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class RepositoryConnectionRepositoryAdapter implements RepositoryConnectionRepository {

    private final RepositoryConnectionJpaRepository jpa;

    RepositoryConnectionRepositoryAdapter(RepositoryConnectionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public RepositoryConnection create(RepositoryConnection connection) {
        return jpa.save(RepositoryConnectionEntity.fromDomain(connection)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryConnection> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId)
            .map(RepositoryConnectionEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepositoryConnection> listForProject(UUID orgId, UUID projectId) {
        return jpa.findByOrgIdAndProjectIdAndDeletedAtIsNullOrderByCreatedAtAsc(orgId, projectId).stream()
            .map(RepositoryConnectionEntity::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public RepositoryConnection update(RepositoryConnection connection) {
        return jpa.save(RepositoryConnectionEntity.fromDomain(connection)).toDomain();
    }

    @Override
    @Transactional
    public boolean softDelete(UUID id, UUID orgId, Instant deletedAt) {
        return jpa.softDelete(id, orgId, deletedAt) > 0;
    }
}
