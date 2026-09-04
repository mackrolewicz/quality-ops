package com.qualityops.api.result.dto;

import java.util.List;
import java.util.UUID;

/** ADR-008 §2 — top-{@code limit} slowest {@code test_case_id}s by p95 duration over the window. */
public record SlowTestsResponse(UUID projectId, int days, int limit, List<SlowTestRow> tests) {}
