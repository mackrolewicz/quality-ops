package com.qualityops.api.testsuite.application.port.in;

import com.qualityops.api.testsuite.domain.TestCase;

import java.util.List;
import java.util.UUID;

public interface ListTestCasesForSuiteUseCase {

    /**
     * Returns all non-deleted test cases for a suite, ordered by orderIndex,
     * without pagination. Used by the execution module (to build a run's
     * config snapshot) and the result module (to generate per-case results).
     */
    List<TestCase> listAllForSuite(UUID suiteId, UUID orgId);
}
