package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.dto.RunResponse;

import java.util.UUID;

public interface TriggerRunUseCase {
    RunResponse trigger(CreateRunRequest request, UUID orgId, UUID triggeredBy);
}
