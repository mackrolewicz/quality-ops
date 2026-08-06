package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.testsuite.dto.CreateTestCaseRequest;
import com.qualityops.api.testsuite.dto.TestCaseResponse;

import java.util.UUID;

public interface CreateTestCaseUseCase {
    TestCaseResponse create(UUID suiteId, CreateTestCaseRequest request, UUID orgId);
}
