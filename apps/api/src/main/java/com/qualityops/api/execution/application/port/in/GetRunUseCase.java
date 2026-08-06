package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.dto.RunResponse;

import java.util.UUID;

public interface GetRunUseCase {
    RunResponse get(UUID id, UUID orgId);

    /**
     * Returns the domain record so other modules (e.g. result) can
     * validate run ownership without depending on this module's DTOs.
     */
    TestRun getDomain(UUID id, UUID orgId);
}
