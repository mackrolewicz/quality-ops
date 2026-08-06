package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.testsuite.dto.TestCaseResponse;

import java.util.UUID;

public interface ListTestCasesUseCase {
    PageResult<TestCaseResponse> list(UUID suiteId, UUID orgId, int page, int size);
}
