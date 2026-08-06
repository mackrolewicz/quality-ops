package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.testsuite.dto.CreateTestSuiteRequest;
import com.qualityops.api.testsuite.dto.TestSuiteResponse;

import java.util.UUID;

public interface CreateTestSuiteUseCase {
    TestSuiteResponse create(UUID projectId, CreateTestSuiteRequest request, UUID orgId);
}
