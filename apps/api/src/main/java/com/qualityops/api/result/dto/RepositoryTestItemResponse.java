package com.qualityops.api.result.dto;

import com.qualityops.api.result.domain.RepositoryTestItem;

/** ADR-009 §11 — a parsed per-test row on the {@code GET /api/v1/runs/{id}/results}
 *  payload. {@code failureMessage} may be null (suppressed by
 *  {@code persist-report-snippets=false}). */
public record RepositoryTestItemResponse(
    String suite,
    String name,
    String status,
    Integer durationMs,
    String failureType,
    String failureMessage
) {
    public static RepositoryTestItemResponse from(RepositoryTestItem item) {
        return new RepositoryTestItemResponse(item.suite(), item.name(), item.status(),
            item.durationMs(), item.failureType(), item.failureMessage());
    }
}
