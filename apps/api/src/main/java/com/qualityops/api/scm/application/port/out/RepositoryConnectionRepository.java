package com.qualityops.api.scm.application.port.out;

import com.qualityops.api.scm.domain.RepositoryConnection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADR-009 §4 — persistence port for {@link RepositoryConnection}. Every method
 *  is org-scoped; soft-deleted rows ({@code deleted_at IS NOT NULL}) are excluded
 *  from reads. */
public interface RepositoryConnectionRepository {

    RepositoryConnection create(RepositoryConnection connection);

    Optional<RepositoryConnection> findByIdAndOrgId(UUID id, UUID orgId);

    List<RepositoryConnection> listForProject(UUID orgId, UUID projectId);

    RepositoryConnection update(RepositoryConnection connection);

    boolean softDelete(UUID id, UUID orgId, Instant deletedAt);
}
