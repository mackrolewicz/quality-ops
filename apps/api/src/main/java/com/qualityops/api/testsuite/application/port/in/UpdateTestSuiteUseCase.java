package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.testsuite.dto.TestSuiteResponse;
import com.qualityops.api.testsuite.dto.UpdateTestSuiteRequest;

import java.util.UUID;

public interface UpdateTestSuiteUseCase {
    TestSuiteResponse update(UUID id, UpdateTestSuiteRequest request, UUID orgId);
}
