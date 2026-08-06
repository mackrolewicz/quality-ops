package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.dto.RunResponse;

import java.util.UUID;

public interface ListRunsUseCase {
    PageResult<RunResponse> list(UUID orgId, UUID projectIdFilter, UUID suiteIdFilter,
                                  RunStatus statusFilter, int page, int size);
}
