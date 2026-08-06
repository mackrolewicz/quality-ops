package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.api.testsuite.dto.TestSuiteResponse;

import java.util.UUID;

public interface GetTestSuiteUseCase {
    TestSuiteResponse get(UUID id, UUID orgId);

    /**
     * Returns the domain record so other modules (e.g. execution) can
     * validate suite ownership without depending on this module's DTOs.
     */
    TestSuite getDomain(UUID id, UUID orgId);
}
