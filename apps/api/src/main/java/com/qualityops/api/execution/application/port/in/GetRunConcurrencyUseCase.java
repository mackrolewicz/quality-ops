package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.dto.RunConcurrencyResponse;

import java.util.UUID;

/** ADR-007 §4 — read the effective per-org concurrency value + its source. */
public interface GetRunConcurrencyUseCase {

    RunConcurrencyResponse get(UUID orgId);
}
