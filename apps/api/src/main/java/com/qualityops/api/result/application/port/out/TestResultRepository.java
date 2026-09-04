package com.qualityops.api.result.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.result.domain.TestResult;

import java.util.UUID;

public interface TestResultRepository {

    /**
     * Epoch-guarded upsert of one case's result, keyed by
     * {@code (run_id, test_case_id)}. If the stored row's {@code attempt_epoch}
     * is already higher than {@link TestResult#attemptEpoch()}, the call is a
     * no-op (a stale or reordered chunk from a superseded attempt).
     */
    void upsert(TestResult result);

    PageResult<TestResult> findAllByRunIdAndOrgId(UUID runId, UUID orgId, int page, int size);
}
