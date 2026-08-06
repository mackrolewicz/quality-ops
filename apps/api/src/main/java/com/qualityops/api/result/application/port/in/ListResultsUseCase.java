package com.qualityops.api.result.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.result.dto.TestResultResponse;

import java.util.UUID;

public interface ListResultsUseCase {

    PageResult<TestResultResponse> list(UUID runId, UUID orgId, int page, int size);
}
