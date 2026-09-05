package com.qualityops.api.result.application.port.in;

import com.qualityops.api.result.dto.RepositoryTestItemResponse;

import java.util.List;
import java.util.UUID;

/** ADR-009 §11 — the parsed per-test rows for a repository run, attached to the
 *  run's results payload. Empty for a non-repository run. */
public interface ListRepositoryItemsUseCase {

    List<RepositoryTestItemResponse> listRepositoryItems(UUID runId, UUID orgId);
}
