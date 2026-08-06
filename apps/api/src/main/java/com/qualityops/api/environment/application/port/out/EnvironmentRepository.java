package com.qualityops.api.environment.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.environment.domain.Environment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentRepository {
    Environment save(Environment environment);

    Optional<Environment> findByIdAndOrgId(UUID id, UUID orgId);

    PageResult<Environment> findAllByProjectIdAndOrgId(UUID projectId, UUID orgId, int page, int size);

    void softDelete(UUID id, UUID orgId, Instant deletedAt);
}
