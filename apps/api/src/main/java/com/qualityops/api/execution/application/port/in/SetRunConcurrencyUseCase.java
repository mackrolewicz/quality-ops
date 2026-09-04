package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.dto.RunConcurrencyResponse;

import java.util.UUID;

/** ADR-007 §4 — org admin sets their own per-org max concurrent active runs. */
public interface SetRunConcurrencyUseCase {

    RunConcurrencyResponse set(UUID orgId, int maxActiveRuns, UUID actorUserId);
}
