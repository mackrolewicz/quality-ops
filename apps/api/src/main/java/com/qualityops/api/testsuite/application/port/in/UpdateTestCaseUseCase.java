package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.testsuite.dto.TestCaseResponse;
import com.qualityops.api.testsuite.dto.UpdateTestCaseRequest;

import java.util.UUID;

public interface UpdateTestCaseUseCase {
    TestCaseResponse update(UUID id, UpdateTestCaseRequest request, UUID orgId);
}
