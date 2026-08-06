package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.dto.TestSuiteResponse;

import java.util.UUID;

public interface ListTestSuitesUseCase {
    PageResult<TestSuiteResponse> list(UUID projectId, UUID orgId, int page, int size);
}
