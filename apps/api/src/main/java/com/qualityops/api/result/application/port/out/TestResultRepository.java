package com.qualityops.api.result.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.result.domain.TestResult;

import java.util.List;
import java.util.UUID;

public interface TestResultRepository {

    void saveAll(List<TestResult> results);

    PageResult<TestResult> findAllByRunIdAndOrgId(UUID runId, UUID orgId, int page, int size);

    boolean existsByRunId(UUID runId, UUID orgId);
}
