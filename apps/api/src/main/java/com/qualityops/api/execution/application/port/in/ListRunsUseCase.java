package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.dto.RunResponse;

import java.util.UUID;

public interface ListRunsUseCase {

    /** Lists runs for the tenant. {@code queueStateFilter} (nullable) restricts to
     *  runs whose {@code run_queue} row is in that state; pre-2C runs with no queue
     *  row are excluded when it is set. */
    PageResult<RunResponse> list(UUID orgId, UUID projectIdFilter, UUID suiteIdFilter,
                                  RunStatus statusFilter, QueueState queueStateFilter, int page, int size);
}
