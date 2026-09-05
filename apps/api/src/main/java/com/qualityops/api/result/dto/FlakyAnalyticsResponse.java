package com.qualityops.api.result.dto;

import java.util.List;
import java.util.UUID;

/** ADR-008 §1 — per-{@code test_case_id} flakiness/stability over the last {@code window} results. */
public record FlakyAnalyticsResponse(UUID projectId, int window, List<FlakyTestRow> tests) {}
