package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.dto.TestCaseResponse;

import java.util.UUID;

public interface GetTestCaseUseCase {
    TestCaseResponse get(UUID id, UUID orgId);

    /**
     * Returns the domain record so other modules can validate test case
     * ownership without depending on this module's DTOs.
     */
    TestCase getDomain(UUID id, UUID orgId);
}
