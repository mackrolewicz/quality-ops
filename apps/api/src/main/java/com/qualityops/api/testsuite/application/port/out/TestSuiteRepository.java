package com.qualityops.api.testsuite.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.domain.TestSuite;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TestSuiteRepository {
    TestSuite save(TestSuite suite);

    Optional<TestSuite> findByIdAndOrgId(UUID id, UUID orgId);

    PageResult<TestSuite> findAllByProjectIdAndOrgId(UUID projectId, UUID orgId, int page, int size);

    void softDelete(UUID id, UUID orgId, Instant deletedAt);
}
