package com.qualityops.api.scm.application.port.in;

import com.qualityops.api.scm.dto.RegisterRepositoryConnectionRequest;
import com.qualityops.api.scm.dto.RepositoryConnectionResponse;
import com.qualityops.api.scm.dto.UpdateRepositoryConnectionRequest;

import java.util.List;
import java.util.UUID;

/** ADR-009 §11 — OWNER/ADMIN CRUD over a project's repository connections. */
public interface ManageRepositoryConnectionsUseCase {

    RepositoryConnectionResponse register(UUID projectId, UUID orgId,
                                          RegisterRepositoryConnectionRequest request, UUID userId);

    List<RepositoryConnectionResponse> list(UUID projectId, UUID orgId);

    RepositoryConnectionResponse get(UUID id, UUID orgId);

    RepositoryConnectionResponse update(UUID id, UUID orgId, UpdateRepositoryConnectionRequest request);

    void delete(UUID id, UUID orgId);
}
