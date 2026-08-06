package com.qualityops.api.testsuite.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.domain.TestCase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestCaseRepository {
    TestCase save(TestCase testCase);

    Optional<TestCase> findByIdAndOrgId(UUID id, UUID orgId);

    PageResult<TestCase> findAllBySuiteIdAndOrgId(UUID suiteId, UUID orgId, int page, int size);

    List<TestCase> findAllBySuiteIdAndOrgIdOrderByOrderIndex(UUID suiteId, UUID orgId);

    Optional<Integer> findMaxOrderIndexBySuiteId(UUID suiteId, UUID orgId);

    void softDelete(UUID id, UUID orgId, Instant deletedAt);
}
